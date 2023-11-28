package io.github.linkedfactory.kvin.parquet;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.github.linkedfactory.kvin.Kvin;
import io.github.linkedfactory.kvin.KvinListener;
import io.github.linkedfactory.kvin.KvinTuple;
import io.github.linkedfactory.kvin.Record;
import io.github.linkedfactory.kvin.util.AggregatingIterator;
import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.commons.iterator.NiceIterator;
import net.enilink.commons.util.Pair;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.io.api.Binary;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.linkedfactory.kvin.parquet.ParquetHelpers.*;
import static io.github.linkedfactory.kvin.parquet.Records.decodeRecord;
import static io.github.linkedfactory.kvin.parquet.Records.encodeRecord;
import static org.apache.parquet.filter2.predicate.FilterApi.*;

public class KvinParquet implements Kvin {
	// used by reader
	final Cache<URI, Long> itemIdCache = CacheBuilder.newBuilder().maximumSize(10000).build();
	final Cache<URI, Long> propertyIdCache = CacheBuilder.newBuilder().maximumSize(10000).build();
	final Cache<URI, Long> contextIdCache = CacheBuilder.newBuilder().maximumSize(10000).build();
	// Lock
	final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
	final Lock writeLock = readWriteLock.writeLock();
	final Lock readLock = readWriteLock.readLock();
	Map<Path, HadoopInputFile> inputFileCache = new HashMap<>(); // hadoop input file cache
	Cache<Long, String> propertyIdReverseLookUpCache = CacheBuilder.newBuilder().maximumSize(10000).build();
	String archiveLocation;

	long itemIdCounter = 0, propertyIdCounter = 0, contextIdCounter = 0; // global id counter
	WriteContext writeContext = new WriteContext();
	// compaction scheduler
	ScheduledExecutorService scheduledExecutorService = new ScheduledThreadPoolExecutor(1);
	Future<?> compactionTaskfuture;

	public KvinParquet(String archiveLocation) {
		this.archiveLocation = archiveLocation;
	}

	public void startCompactionWorker(long initialDelay, long period, TimeUnit unit) {
		//compactionTaskfuture = scheduledExecutorService.scheduleAtFixedRate(new CompactionWorker(archiveLocation, this), initialDelay, period, unit);
	}

	public void stopCompactionWorker() {
		if (compactionTaskfuture != null) {
			compactionTaskfuture.cancel(true);
		}
	}

	private IdMapping fetchMappingIds(Path mappingFile, FilterPredicate filter) throws IOException {
		IdMapping id;
		HadoopInputFile inputFile = getFile(mappingFile);
		try (ParquetReader<IdMapping> reader = AvroParquetReader.<IdMapping>builder(inputFile)
				.withDataModel(reflectData)
				.useStatsFilter()
				.withFilter(FilterCompat.get(filter))
				.build()) {
			id = reader.read();
		}
		return id;
	}

	private HadoopInputFile getFile(Path path) {
		HadoopInputFile inputFile;
		synchronized (inputFileCache) {
			inputFile = inputFileCache.get(path);
			if (inputFile == null) {
				try {
					inputFile = HadoopInputFile.fromPath(path, new Configuration());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				inputFileCache.put(path, inputFile);
			}
		}
		return inputFile;
	}

	@Override
	public boolean addListener(KvinListener listener) {
		return false;
	}

	@Override
	public boolean removeListener(KvinListener listener) {
		return false;
	}

	@Override
	public void put(KvinTuple... tuples) {
		this.put(Arrays.asList(tuples));
	}

	@Override
	public void put(Iterable<KvinTuple> tuples) {
		try {
			putInternal(tuples);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void putInternal(Iterable<KvinTuple> tuples) throws IOException {
		try {
			writeLock.lock();
			Map<String, WriterState> writers = new HashMap<>();

			java.nio.file.Path tempPath = Paths.get(archiveLocation, ".tmp");
			Files.createDirectories(tempPath);
			Path itemMappingFile = new Path(tempPath.toString(), "metadata/itemMapping__1.parquet");
			Path propertyMappingFile = new Path(tempPath.toString(), "metadata/propertyMapping__1.parquet");
			Path contextMappingFile = new Path(tempPath.toString(), "metadata/contextMapping__1.parquet");

			ParquetWriter<Object> itemMappingWriter = getParquetMappingWriter(itemMappingFile);
			ParquetWriter<Object> propertyMappingWriter = getParquetMappingWriter(propertyMappingFile);
			ParquetWriter<Object> contextMappingWriter = getParquetMappingWriter(contextMappingFile);

			WriterState writerState = null;
			String prevKey = null;
			for (KvinTuple tuple : tuples) {
				KvinTupleInternal internalTuple = new KvinTupleInternal();

				Calendar tupleDate = getDate(tuple.time);
				int year = tupleDate.get(Calendar.YEAR);
				int week = tupleDate.get(Calendar.WEEK_OF_YEAR);

				String key = year + "_" + week;
				if (!key.equals(prevKey)) {
					writerState = writers.get(key);
					if (writerState == null) {
						java.nio.file.Path file = tempPath.resolve(key + "_data.parquet");
						writerState = new WriterState(file, getParquetDataWriter(new Path(file.toString())),
								year, week);
						writers.put(key, writerState);
					}
					prevKey = key;
				}

				// writing mappings and values
				internalTuple.setId(generateId(tuple, writeContext,
						itemMappingWriter, propertyMappingWriter, contextMappingWriter));
				internalTuple.setTime(tuple.time);
				internalTuple.setSeqNr(tuple.seqNr);

				internalTuple.setValueInt(tuple.value instanceof Integer ? (int) tuple.value : null);
				internalTuple.setValueLong(tuple.value instanceof Long ? (long) tuple.value : null);
				internalTuple.setValueFloat(tuple.value instanceof Float ? (float) tuple.value : null);
				internalTuple.setValueDouble(tuple.value instanceof Double ? (double) tuple.value : null);
				internalTuple.setValueString(tuple.value instanceof String ? (String) tuple.value : null);
				internalTuple.setValueBool(tuple.value instanceof Boolean ? (Boolean) tuple.value ? 1 : 0 : null);
				if (tuple.value instanceof Record || tuple.value instanceof URI || tuple.value instanceof BigInteger ||
						tuple.value instanceof BigDecimal || tuple.value instanceof Short) {
					internalTuple.setValueObject(encodeRecord(tuple.value));
				} else {
					internalTuple.setValueObject(null);
				}
				writerState.writer.write(internalTuple);
				writerState.minMax[0] = Math.min(writerState.minMax[0], itemIdCounter);
				writerState.minMax[1] = Math.max(writerState.minMax[1], itemIdCounter);
			}

			for (WriterState state : writers.values()) {
				state.writer.close();
			}
			itemMappingWriter.close();
			contextMappingWriter.close();
			propertyMappingWriter.close();

			Map<Integer, java.nio.file.Path> yearFolders = new HashMap<>();
			List<java.nio.file.Path> existingPaths = Files.walk(Paths.get(archiveLocation), 1).skip(1)
					.flatMap(parent -> {
						if (!tempPath.equals(parent) && Files.isDirectory(parent)) {
							try {
								int year = Integer.parseInt(parent.getFileName().toString().split("_")[0]);
								yearFolders.put(year, parent);

								// TODO maybe directly filter non-relevant years and weeks
								try {
									return Files.walk(parent, 1).skip(1);
								} catch (IOException e) {
									throw new RuntimeException(e);
								}
							} catch (NumberFormatException e) {
								return Stream.empty();
							}
						} else {
							return Stream.empty();
						}
					}).collect(Collectors.toList());

			Map<Integer, long[]> minMaxYears = new HashMap<>();
			for (WriterState state : writers.values()) {
				minMaxYears.compute(state.year, (k, v) -> {
					if (v == null) {
						return Arrays.copyOf(state.minMax, state.minMax.length);
					} else {
						v[0] = Math.min(v[0], state.minMax[0]);
						v[1] = Math.max(v[1], state.minMax[1]);
						return v;
					}
				});
			}

			existingPaths.forEach(p -> {
				String yearFolder = p.getParent().getFileName().toString();
				String weekFolder = p.getFileName().toString();
				int year = Integer.parseInt(yearFolder.split("_")[0]);
				int week = Integer.parseInt(weekFolder.split("_")[0]);

				String key = year + "_" + week;
				WriterState state = writers.get(key);
				if (state != null) {
					state.existingWeek = weekFolder;

					long[] minMaxWeek = toMinMaxId(weekFolder);
					if (minMaxWeek != null) {
						state.minMax[0] = Math.min(state.minMax[0], minMaxWeek[0]);
						state.minMax[1] = Math.max(state.minMax[1], minMaxWeek[1]);
					}
				}
				minMaxYears.computeIfPresent(year, (k, v) -> {
					long[] minMaxYear = toMinMaxId(yearFolder);
					v[0] = Math.min(v[0], minMaxYear[0]);
					v[1] = Math.max(v[1], minMaxYear[1]);
					return v;
				});
			});


			Map<Integer, List<WriterState>> writersPerYear = writers.values().stream()
					.collect(Collectors.groupingBy(s -> s.year));
			for (Map.Entry<Integer, List<WriterState>> entry : writersPerYear.entrySet()) {
				int year = entry.getKey();
				java.nio.file.Path yearFolder = yearFolders.get(year);
				long[] minMaxYear = minMaxYears.get(year);
				String yearFolderName = String.format("%04d", year) + "_" + minMaxYear[0] + "-" + minMaxYear[1];
				if (yearFolder != null) {
					yearFolder = Files.move(yearFolder, yearFolder.resolveSibling(yearFolderName));
				} else {
					yearFolder = Files.createDirectory(Paths.get(archiveLocation, yearFolderName));
				}
				for (WriterState state : entry.getValue()) {
					String weekFolderName = String.format("%02d", state.week) + "_" +
							state.minMax[0] + "-" + state.minMax[1];
					java.nio.file.Path weekFolder;
					if (state.existingWeek != null) {
						weekFolder = Files.move(yearFolder.resolve(state.existingWeek), yearFolder.resolve(weekFolderName));
					} else {
						weekFolder = Files.createDirectory(yearFolder.resolve(weekFolderName));
					}
					int maxSeqNr = Files.list(weekFolder).map(p -> {
						String name = p.getFileName().toString();
						if (name.startsWith("data")) {
							Matcher m = fileWithSeqNr.matcher(name);
							if (m.matches()) {
								return Integer.parseInt(m.group(2));
							}
						}
						return 0;
					}).max(Integer::compareTo).orElse(0);
					String filename = "data__" + (maxSeqNr + 1) + ".parquet";
					Files.move(state.file, weekFolder.resolve(filename));
				}
			}

			java.nio.file.Path tempMetadataPath = tempPath.resolve("metadata");
			java.nio.file.Path metadataPath = Paths.get(archiveLocation, "metadata");
			Files.createDirectories(metadataPath);
			Map<String, List<Pair<String, Integer>>> newMappingFiles = getMappingFiles(tempMetadataPath);
			Map<String, List<Pair<String, Integer>>> existingMappingFiles = getMappingFiles(metadataPath);
			for (Map.Entry<String, List<Pair<String, Integer>>> newMapping : newMappingFiles.entrySet()) {
				int seqNr = Optional.ofNullable(existingMappingFiles.get(newMapping.getKey()))
						.filter(l -> !l.isEmpty())
						.map(l -> l.get(l.size() - 1).getSecond())
						.orElse(0) + 1;
				Files.move(tempMetadataPath.resolve(newMapping.getValue().get(0).getFirst()),
						metadataPath.resolve(newMapping.getKey() + "__" + seqNr + ".parquet"));
			}

			// completely delete temporary directory
			Files.walk(tempPath).sorted(Comparator.reverseOrder())
					.map(java.nio.file.Path::toFile)
					.forEach(File::delete);
		} finally {
			writeLock.unlock();
		}
	}

	private Calendar getDate(long timestamp) {
		Timestamp ts = new Timestamp(timestamp * 1000);
		Date date = new java.sql.Date(ts.getTime());
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(date);
		return calendar;
	}

	private byte[] generateId(KvinTuple currentTuple,
	                          WriteContext writeContext,
	                          ParquetWriter itemMappingWriter,
	                          ParquetWriter propertyMappingWriter,
	                          ParquetWriter contextMappingWriter) {
		long itemId = writeContext.itemMap.computeIfAbsent(currentTuple.item.toString(), key -> {
			long newId = ++itemIdCounter;
			IdMapping mapping = new SimpleMapping();
			mapping.setId(newId);
			mapping.setValue(key);
			try {
				itemMappingWriter.write(mapping);
			} catch (IOException e) {
				throw new RuntimeException();
			}
			return newId;
		});
		long propertyId = writeContext.propertyMap.computeIfAbsent(currentTuple.property.toString(), key -> {
			long newId = ++propertyIdCounter;
			IdMapping mapping = new SimpleMapping();
			mapping.setId(newId);
			mapping.setValue(key);
			try {
				propertyMappingWriter.write(mapping);
			} catch (IOException e) {
				throw new RuntimeException();
			}
			return newId;
		});

		long contextId = writeContext.contextMap.computeIfAbsent(currentTuple.context.toString(), key -> {
			long newId = ++contextIdCounter;
			IdMapping mapping = new SimpleMapping();
			mapping.setId(newId);
			mapping.setValue(key);
			try {
				contextMappingWriter.write(mapping);
			} catch (IOException e) {
				throw new RuntimeException();
			}
			return newId;
		});

		ByteBuffer idBuffer = ByteBuffer.allocate(Long.BYTES * 3);
		idBuffer.putLong(itemId);
		idBuffer.putLong(propertyId);
		idBuffer.putLong(contextId);
		return idBuffer.array();
	}

	private long getId(URI entity, IdType idType) {
		Cache<URI, Long> idCache;
		switch (idType) {
			case ITEM_ID:
				idCache = itemIdCache;
				break;
			case PROPERTY_ID:
				idCache = propertyIdCache;
				break;
			default:
				//case CONTEXT_ID:
				idCache = contextIdCache;
				break;
		}
		Long id;
		try {
			id = idCache.get(entity, () -> {
				// read from files
				String name;
				switch (idType) {
					case ITEM_ID:
						name = "item";
						break;
					case PROPERTY_ID:
						name = "property";
						break;
					default:
						//case CONTEXT_ID:
						name = "context";
						break;
				}
				FilterPredicate filter = eq(FilterApi.binaryColumn("value"), Binary.fromString(entity.toString()));
				Path metadataFolder = new Path(this.archiveLocation + "metadata/");
				File[] mappingFiles = new File(metadataFolder.toString()).listFiles((file, s) -> s.startsWith(name + "Mapping"));

				IdMapping mapping = null;
				for (File mappingFile : mappingFiles) {
					mapping = fetchMappingIds(new Path(mappingFile.getPath()), filter);
					if (mapping != null) break;
				}
				return mapping != null ? mapping.getId() : 0L;
			});
		} catch (ExecutionException e) {
			return 0L;
		}
		return id != null ? id : 0L;
	}

	private IdMappings getIdMappings(URI item, URI property, URI context) throws IOException {
		final IdMappings mappings = new IdMappings();
		if (item != null) {
			mappings.itemId = getId(item, IdType.ITEM_ID);
		}
		if (property != null) {
			mappings.propertyId = getId(property, IdType.PROPERTY_ID);
		}
		if (context != null) {
			mappings.contextId = getId(context, IdType.CONTEXT_ID);
		}
		return mappings;
	}

	private FilterPredicate generateFetchFilter(IdMappings idMappings) {
		if (idMappings.propertyId != 0L) {
			ByteBuffer keyBuffer = ByteBuffer.allocate(Long.BYTES * 3);
			keyBuffer.putLong(idMappings.itemId);
			keyBuffer.putLong(idMappings.propertyId);
			keyBuffer.putLong(idMappings.contextId);
			return eq(FilterApi.binaryColumn("id"), Binary.fromConstantByteArray(keyBuffer.array()));
		} else {
			ByteBuffer keyBuffer = ByteBuffer.allocate(Long.BYTES);
			keyBuffer.putLong(idMappings.itemId);
			return and(gt(FilterApi.binaryColumn("id"), Binary.fromConstantByteArray(keyBuffer.array())),
					lt(FilterApi.binaryColumn("id"),
							Binary.fromConstantByteArray(ByteBuffer.allocate(Long.BYTES)
									.putLong(idMappings.itemId + 1).array())));
		}
	}

	private FilterPredicate generatePropertyFetchFilter(IdMappings idMappings) {
		if (idMappings.propertyId != 0L) {
			ByteBuffer keyBuffer = ByteBuffer.allocate(Long.BYTES * 2);
			keyBuffer.putLong(idMappings.itemId);
			keyBuffer.putLong(idMappings.propertyId);

			return and(gt(FilterApi.binaryColumn("id"), Binary.fromConstantByteArray(keyBuffer.array())),
					lt(FilterApi.binaryColumn("id"),
							Binary.fromConstantByteArray(ByteBuffer.allocate(Long.BYTES * 2)
									.putLong(idMappings.itemId).putLong(idMappings.propertyId + 1).array())));
		} else {
			ByteBuffer keyBuffer = ByteBuffer.allocate(Long.BYTES);
			keyBuffer.putLong(idMappings.itemId);
			return and(gt(FilterApi.binaryColumn("id"), Binary.fromConstantByteArray(keyBuffer.array())),
					lt(FilterApi.binaryColumn("id"),
							Binary.fromConstantByteArray(ByteBuffer.allocate(Long.BYTES)
									.putLong(idMappings.itemId + 1).array())));
		}
	}

	@Override
	public IExtendedIterator<KvinTuple> fetch(URI item, URI property, URI context, long limit) {
		return fetchInternal(item, property, context, null, null, limit);
	}

	@Override
	public IExtendedIterator<KvinTuple> fetch(URI item, URI property, URI context, long end, long begin, long limit, long interval, String op) {
		IExtendedIterator<KvinTuple> internalResult = fetchInternal(item, property, context, end, begin, limit);
		if (op != null) {
			internalResult = new AggregatingIterator<>(internalResult, interval, op.trim().toLowerCase(), limit) {
				@Override
				protected KvinTuple createElement(URI item, URI property, URI context, long time, int seqNr, Object value) {
					return new KvinTuple(item, property, context, time, seqNr, value);
				}
			};
		}
		return internalResult;
	}

	public String getProperty(KvinTupleInternal tuple) {
		ByteBuffer idBuffer = ByteBuffer.wrap(tuple.getId());
		idBuffer.getLong();
		Long propertyId = idBuffer.getLong();
		String cachedProperty = propertyIdReverseLookUpCache.getIfPresent(propertyId);

		if (cachedProperty == null) {
			try {
				FilterPredicate filter = eq(FilterApi.longColumn("id"), propertyId);
				Path metadataFolder = new Path(this.archiveLocation + "metadata/");
				File[] mappingFiles = new File(metadataFolder.toString()).listFiles((file, s) -> s.startsWith("propertyMapping"));
				IdMapping propertyMapping = null;

				for (File mappingFile : mappingFiles) {
					propertyMapping = fetchMappingIds(new Path(mappingFile.getPath()), filter);
					if (propertyMapping != null) break;
				}

				cachedProperty = propertyMapping.getValue();
				propertyIdReverseLookUpCache.put(propertyId, propertyMapping.getValue());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return cachedProperty;
	}

	private synchronized IExtendedIterator<KvinTuple> fetchInternal(URI item, URI property, URI context, Long end, Long begin, Long limit) {
		try {
			readLock.lock();
			// filters
			IdMappings idMappings = getIdMappings(item, property, context);
			if (idMappings.itemId == 0L) {
				return NiceIterator.emptyIterator();
			}

			FilterPredicate filter = generateFetchFilter(idMappings);
			if (begin != null) {
				filter = and(filter, gtEq(FilterApi.longColumn("time"), begin));
			}
			if (end != null) {
				filter = and(filter, lt(FilterApi.longColumn("time"), end));
			}

			final FilterPredicate filterFinal = filter;
			List<List<Path>> dataFiles = getFilePath(idMappings);
			return new NiceIterator<KvinTuple>() {
				KvinTupleInternal internalTuple;
				List<ParquetReader<KvinTupleInternal>> readers;
				KvinTupleInternal[] nextTuples;
				long propertyValueCount;
				int fileIndex = -1;
				String currentProperty;

				{
					try {
						nextReaders();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}

				KvinTupleInternal selectNextTuple() throws IOException {
					int minIndex = 0;
					KvinTupleInternal minTuple = null;
					for (int i = 0; i < nextTuples.length; i++) {
						KvinTupleInternal candidate = nextTuples[i];
						if (candidate == null) {
							continue;
						}
						if (minTuple == null ||
								candidate.time < minTuple.time ||
								candidate.time.equals(minTuple.time) &&
										candidate.seqNr < minTuple.seqNr
						) {
							minTuple = candidate;
							minIndex = i;
						}
					}
					if (minTuple != null) {
						nextTuples[minIndex] = readers.get(minIndex).read();
					}
					return minTuple;
				}

				@Override
				public boolean hasNext() {
					try {
						// skipping properties if limit is reached
						if (limit != 0 && propertyValueCount >= limit) {
							while ((internalTuple = selectNextTuple()) != null) {
								String property = getProperty(internalTuple);
								if (!property.equals(currentProperty)) {
									propertyValueCount = 0;
									currentProperty = property;
									break;
								}
							}
						}
						internalTuple = selectNextTuple();

						if (internalTuple == null && fileIndex >= dataFiles.size() - 1) { // terminating condition
							closeCurrentReaders();
							return false;
						} else if (internalTuple == null && fileIndex < dataFiles.size() - 1 && propertyValueCount >= limit && limit != 0) { // moving on to the next reader upon limit reach
							closeCurrentReaders();
							nextReaders();
							return hasNext();
						} else if (internalTuple == null && fileIndex < dataFiles.size() - 1) { // moving on to the next available reader
							closeCurrentReaders();
							nextReaders();
							internalTuple = selectNextTuple();
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					return true;
				}

				@Override
				public KvinTuple next() {
					if (internalTuple == null) {
						throw new NoSuchElementException();
					} else {
						KvinTupleInternal tuple = internalTuple;
						internalTuple = null;
						return internalTupleToKvinTuple(tuple);
					}
				}

				private KvinTuple internalTupleToKvinTuple(KvinTupleInternal internalTuple) {
					Object value = null;
					if (internalTuple.valueInt != null) {
						value = internalTuple.valueInt;
					} else if (internalTuple.valueLong != null) {
						value = internalTuple.valueLong;
					} else if (internalTuple.valueFloat != null) {
						value = internalTuple.valueFloat;
					} else if (internalTuple.valueDouble != null) {
						value = internalTuple.valueDouble;
					} else if (internalTuple.valueString != null) {
						value = internalTuple.valueString;
					} else if (internalTuple.valueBool != null) {
						value = internalTuple.valueBool == 1;
					} else if (internalTuple.valueObject != null) {
						value = decodeRecord(internalTuple.valueObject);
					}

					// checking for property change
					String property = getProperty(internalTuple);
					if (currentProperty == null) {
						currentProperty = property;
					} else if (!property.equals(currentProperty)) {
						currentProperty = property;
						propertyValueCount = 0;
					}

					propertyValueCount++;
					return new KvinTuple(item, URIs.createURI(property), context,
							internalTuple.time, internalTuple.seqNr, value);
				}

				@Override
				public void close() {
					closeCurrentReaders();
					readLock.unlock();
				}

				void nextReaders() throws IOException {
					fileIndex++;
					List<Path> currentFiles = dataFiles.get(fileIndex);
					readers = new ArrayList<>();
					for (Path file : currentFiles) {
						HadoopInputFile inputFile = getFile(file);
						readers.add(AvroParquetReader.<KvinTupleInternal>builder(inputFile)
								.withDataModel(reflectData)
								.useStatsFilter()
								.withFilter(FilterCompat.get(filterFinal))
								.build());
					}
					nextTuples = new KvinTupleInternal[readers.size()];
					int i = 0;
					for (ParquetReader<KvinTupleInternal> reader: readers) {
						KvinTupleInternal tuple = reader.read();
						if (tuple != null) {
							nextTuples[i++] = tuple;
						}
					}
				}

				void closeCurrentReaders() {
					if (readers != null) {
						for (ParquetReader<?> reader : readers) {
							try {
								reader.close();
							} catch (IOException e) {
							}
						}
						readers = null;
					}
				}
			};
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public long delete(URI item, URI property, URI context, long end, long begin) {
		return 0;
	}

	@Override
	public boolean delete(URI item) {
		return false;
	}

	private long[] toMinMaxId(String name) {
		String[] partitionMinMax = name.split("_");
		if (partitionMinMax.length <= 1) {
			return null;
		}
		String[] minMaxId = partitionMinMax[1].split("-");
		if (minMaxId.length <= 1) {
			return null;
		}
		return new long[]{Long.parseLong(minMaxId[0]), Long.parseLong(minMaxId[1])};
	}

	private List<List<Path>> getFilePath(IdMappings idMappings) {
		long itemId = idMappings.itemId;
		Predicate<java.nio.file.Path> idFilter = p -> {
			long[] minMax = toMinMaxId(p.getFileName().toString());
			if (minMax == null) {
				return false;
			}
			return itemId >= minMax[0] && itemId <= minMax[1];
		};

		try {
			return Files.walk(Paths.get(archiveLocation), 1)
					.skip(1)
					.filter(idFilter)
					.sorted(Comparator.reverseOrder())
					.flatMap(parent -> {
						try {
							return Files.walk(parent, 1)
									.skip(1)
									.filter(idFilter)
									.sorted(Comparator.reverseOrder())
									.map(weekParent -> {
										try {
											return Files.walk(weekParent, 1)
													.skip(1)
													.filter(path ->
															path.getFileName().toString().startsWith("data__"))
													.sorted(Comparator.reverseOrder())
													// convert to Hadoop path
													.map(p -> new Path(p.toString()))
													.collect(Collectors.toList());
										} catch (IOException e) {
											throw new RuntimeException(e);
										}
									});
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					})
					.collect(Collectors.toList());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public IExtendedIterator<URI> descendants(URI item) {
		return null;
	}

	@Override
	public IExtendedIterator<URI> descendants(URI item, long limit) {
		return null;
	}

	private KvinTupleMetadata getFirstTuple(URI item, Long itemId, Long propertyId, Long contextId) {
		IdMappings idMappings = null;
		KvinTupleMetadata foundTuple = null;
		try {
			if (itemId == null) {
				idMappings = getIdMappings(item, null, Kvin.DEFAULT_CONTEXT);
			} else if (item != null && itemId != null && propertyId != null) {
				idMappings = new IdMappings();
				idMappings.itemId = itemId;
				idMappings.propertyId = propertyId;
				idMappings.contextId = contextId;
			}

			FilterPredicate filter = generatePropertyFetchFilter(idMappings);
			List<List<Path>> dataFiles = getFilePath(idMappings);
			ParquetReader<KvinTupleInternal> reader;

			HadoopInputFile inputFile = getFile(dataFiles.get(0).get(0));
			reader = AvroParquetReader.<KvinTupleInternal>builder(inputFile)
					.withDataModel(reflectData)
					.useStatsFilter()
					.withFilter(FilterCompat.get(filter))
					.build();
			KvinTupleInternal firstTuple = reader.read();
			reader.close();

			if (firstTuple != null) {
				URI firstTupleProperty = URIs.createURI(getProperty(firstTuple));
				if (itemId == null) {
					idMappings.propertyId = getId(firstTupleProperty, IdType.PROPERTY_ID);
				}
				foundTuple = new KvinTupleMetadata(item.toString(), firstTupleProperty.toString(), idMappings.itemId, idMappings.propertyId, idMappings.contextId);
			}

			return foundTuple;

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public synchronized IExtendedIterator<URI> properties(URI item) {
		readLock.lock();
		return new NiceIterator<>() {
			KvinTupleMetadata currentTuple = getFirstTuple(item, null, null, null);
			KvinTupleMetadata previousTuple = null;

			@Override
			public boolean hasNext() {
				if (currentTuple != null) {
					return true;
				} else {
					currentTuple = getFirstTuple(URIs.createURI(previousTuple.getItem()), previousTuple.getItemId(), previousTuple.getPropertyId() + 1, previousTuple.contextId);
				}
				return currentTuple != null;
			}

			@Override
			public URI next() {
				URI property = URIs.createURI(currentTuple.getProperty());
				previousTuple = currentTuple;
				currentTuple = null;
				return property;
			}

			@Override
			public void close() {
				super.close();
				readLock.unlock();
			}
		};
	}

	@Override
	public long approximateSize(URI item, URI property, URI context, long end, long begin) {
		return 0;
	}

	@Override
	public void close() {
		stopCompactionWorker();
	}

	// id enum
	enum IdType {
		ITEM_ID,
		PROPERTY_ID,
		CONTEXT_ID
	}

	static class WriterState {
		java.nio.file.Path file;
		ParquetWriter<KvinTupleInternal> writer;
		int year;
		int week;
		long[] minMax = {Long.MAX_VALUE, Long.MIN_VALUE};
		String existingWeek;

		WriterState(java.nio.file.Path file, ParquetWriter<KvinTupleInternal> writer, int year, int weak) {
			this.file = file;
			this.writer = writer;
			this.year = year;
			this.week = weak;
		}
	}

	static class IdMappings {
		long itemId, propertyId, contextId;
	}

	class WriteContext {
		// used by writer
		Map<String, Long> itemMap = new HashMap<>();
		Map<String, Long> propertyMap = new HashMap<>();
		Map<String, Long> contextMap = new HashMap<>();
	}
}
