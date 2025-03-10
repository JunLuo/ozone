/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.apache.hadoop.hdds.utils.db;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import org.apache.hadoop.hdds.utils.MetadataKeyFilters;
import org.apache.hadoop.hdds.utils.db.cache.CacheKey;
import org.apache.hadoop.hdds.utils.db.cache.CacheResult;
import org.apache.hadoop.hdds.utils.db.cache.CacheValue;
import org.apache.hadoop.hdds.utils.db.cache.FullTableCache;
import org.apache.hadoop.hdds.utils.db.cache.PartialTableCache;
import org.apache.hadoop.hdds.utils.db.cache.TableCache.CacheType;
import org.apache.hadoop.hdds.utils.db.cache.TableCache;

import static org.apache.hadoop.hdds.utils.db.cache.CacheResult.CacheStatus.EXISTS;
import static org.apache.hadoop.hdds.utils.db.cache.CacheResult.CacheStatus.NOT_EXIST;
/**
 * Strongly typed table implementation.
 * <p>
 * Automatically converts values and keys using a raw byte[] based table
 * implementation and registered converters.
 *
 * @param <KEY>   type of the keys in the store.
 * @param <VALUE> type of the values in the store.
 */
public class TypedTable<KEY, VALUE> implements Table<KEY, VALUE> {

  private final Table<byte[], byte[]> rawTable;

  private final CodecRegistry codecRegistry;

  private final Class<KEY> keyType;

  private final Class<VALUE> valueType;

  private final TableCache<CacheKey<KEY>, CacheValue<VALUE>> cache;

  private static final long EPOCH_DEFAULT = -1L;

  /**
   * Create an TypedTable from the raw table.
   * Default cache type for the table is {@link CacheType#PARTIAL_CACHE}.
   * @param rawTable
   * @param codecRegistry
   * @param keyType
   * @param valueType
   */
  public TypedTable(
      Table<byte[], byte[]> rawTable,
      CodecRegistry codecRegistry, Class<KEY> keyType,
      Class<VALUE> valueType) throws IOException {
    this(rawTable, codecRegistry, keyType, valueType,
        CacheType.PARTIAL_CACHE);
  }

  /**
   * Create an TypedTable from the raw table with specified cache type.
   * @param rawTable
   * @param codecRegistry
   * @param keyType
   * @param valueType
   * @param cacheType
   * @throws IOException
   */
  public TypedTable(
      Table<byte[], byte[]> rawTable,
      CodecRegistry codecRegistry, Class<KEY> keyType,
      Class<VALUE> valueType,
      CacheType cacheType) throws IOException {
    this.rawTable = rawTable;
    this.codecRegistry = codecRegistry;
    this.keyType = keyType;
    this.valueType = valueType;

    if (cacheType == CacheType.FULL_CACHE) {
      cache = new FullTableCache<>();
      //fill cache
      try (TableIterator<KEY, ? extends KeyValue<KEY, VALUE>> tableIterator =
              iterator()) {

        while (tableIterator.hasNext()) {
          KeyValue< KEY, VALUE > kv = tableIterator.next();

          // We should build cache after OM restart when clean up policy is
          // NEVER. Setting epoch value -1, so that when it is marked for
          // delete, this will be considered for cleanup.
          cache.loadInitial(new CacheKey<>(kv.getKey()),
              new CacheValue<>(Optional.of(kv.getValue()), EPOCH_DEFAULT));
        }
      }
    } else {
      cache = new PartialTableCache<>();
    }
  }

  @Override
  public void put(KEY key, VALUE value) throws IOException {
    byte[] keyData = codecRegistry.asRawData(key);
    byte[] valueData = codecRegistry.asRawData(value);
    rawTable.put(keyData, valueData);
  }

  @Override
  public void putWithBatch(BatchOperation batch, KEY key, VALUE value)
      throws IOException {
    byte[] keyData = codecRegistry.asRawData(key);
    byte[] valueData = codecRegistry.asRawData(value);
    rawTable.putWithBatch(batch, keyData, valueData);
  }

  @Override
  public boolean isEmpty() throws IOException {
    return rawTable.isEmpty();
  }

  @Override
  public boolean isExist(KEY key) throws IOException {

    CacheResult<CacheValue<VALUE>> cacheResult =
        cache.lookup(new CacheKey<>(key));

    if (cacheResult.getCacheStatus() == EXISTS) {
      return true;
    } else if (cacheResult.getCacheStatus() == NOT_EXIST) {
      return false;
    } else {
      return rawTable.isExist(codecRegistry.asRawData(key));
    }
  }

  /**
   * Returns the value mapped to the given key in byte array or returns null
   * if the key is not found.
   *
   * Caller's of this method should use synchronization mechanism, when
   * accessing. First it will check from cache, if it has entry return the
   * cloned cache value, otherwise get from the RocksDB table.
   *
   * @param key metadata key
   * @return VALUE
   * @throws IOException
   */
  @Override
  public VALUE get(KEY key) throws IOException {
    // Here the metadata lock will guarantee that cache is not updated for same
    // key during get key.

    CacheResult<CacheValue<VALUE>> cacheResult =
        cache.lookup(new CacheKey<>(key));

    if (cacheResult.getCacheStatus() == EXISTS) {
      return codecRegistry.copyObject(cacheResult.getValue().getCacheValue(),
          valueType);
    } else if (cacheResult.getCacheStatus() == NOT_EXIST) {
      return null;
    } else {
      return getFromTable(key);
    }
  }

  /**
   * Skip checking cache and get the value mapped to the given key in byte
   * array or returns null if the key is not found.
   *
   * @param key metadata key
   * @return value in byte array or null if the key is not found.
   * @throws IOException on Failure
   */
  @Override
  public VALUE getSkipCache(KEY key) throws IOException {
    return getFromTable(key);
  }

  /**
   * This method returns the value if it exists in cache, if it 
   * does not, get the value from the underlying rockdb table. If it 
   * exists in cache, it returns the same reference of the cached value.
   * 
   *
   * Caller's of this method should use synchronization mechanism, when
   * accessing. First it will check from cache, if it has entry return the
   * cached value, otherwise get from the RocksDB table. It is caller
   * responsibility to not to use the returned object outside the lock.
   *
   * One example use case of this method is, when validating volume exists in
   * bucket requests and also where we need actual value of volume info. Once 
   * bucket response is added to the double buffer, only bucket info is 
   * required to flush to DB. So, there is no case of concurrent threads 
   * modifying the same cached object.
   * @param key metadata key
   * @return VALUE
   * @throws IOException
   */
  @Override
  public VALUE getReadCopy(KEY key) throws IOException {
    // Here the metadata lock will guarantee that cache is not updated for same
    // key during get key.

    CacheResult<CacheValue<VALUE>> cacheResult =
        cache.lookup(new CacheKey<>(key));

    if (cacheResult.getCacheStatus() == EXISTS) {
      return cacheResult.getValue().getCacheValue();
    } else if (cacheResult.getCacheStatus() == NOT_EXIST) {
      return null;
    } else {
      return getFromTable(key);
    }
  }

  @Override
  public VALUE getIfExist(KEY key) throws IOException {
    // Here the metadata lock will guarantee that cache is not updated for same
    // key during get key.

    CacheResult<CacheValue<VALUE>> cacheResult =
        cache.lookup(new CacheKey<>(key));

    if (cacheResult.getCacheStatus() == EXISTS) {
      return codecRegistry.copyObject(cacheResult.getValue().getCacheValue(),
          valueType);
    } else if (cacheResult.getCacheStatus() == NOT_EXIST) {
      return null;
    } else {
      return getFromTableIfExist(key);
    }
  }

  private VALUE getFromTable(KEY key) throws IOException {
    byte[] keyBytes = codecRegistry.asRawData(key);
    byte[] valueBytes = rawTable.get(keyBytes);
    return codecRegistry.asObject(valueBytes, valueType);
  }

  private VALUE getFromTableIfExist(KEY key) throws IOException {
    byte[] keyBytes = codecRegistry.asRawData(key);
    byte[] valueBytes = rawTable.getIfExist(keyBytes);
    return codecRegistry.asObject(valueBytes, valueType);
  }

  @Override
  public void delete(KEY key) throws IOException {
    rawTable.delete(codecRegistry.asRawData(key));
  }

  @Override
  public void deleteWithBatch(BatchOperation batch, KEY key)
      throws IOException {
    rawTable.deleteWithBatch(batch, codecRegistry.asRawData(key));

  }

  @Override
  public TableIterator<KEY, TypedKeyValue> iterator() {
    TableIterator<byte[], ? extends KeyValue<byte[], byte[]>> iterator =
        rawTable.iterator();
    return new TypedTableIterator(iterator, keyType, valueType);
  }

  @Override
  public String getName() throws IOException {
    return rawTable.getName();
  }

  @Override
  public long getEstimatedKeyCount() throws IOException {
    return rawTable.getEstimatedKeyCount();
  }

  @Override
  public void close() throws Exception {
    rawTable.close();

  }

  @Override
  public void addCacheEntry(CacheKey<KEY> cacheKey,
      CacheValue<VALUE> cacheValue) {
    // This will override the entry if there is already entry for this key.
    cache.put(cacheKey, cacheValue);
  }

  @Override
  public CacheValue<VALUE> getCacheValue(CacheKey<KEY> cacheKey) {
    return cache.get(cacheKey);
  }

  @Override
  public Iterator<Map.Entry<CacheKey<KEY>, CacheValue<VALUE>>> cacheIterator() {
    return cache.iterator();
  }

  @Override
  public List<TypedKeyValue> getRangeKVs(
          KEY startKey, int count,
          MetadataKeyFilters.MetadataKeyFilter... filters)
          throws IOException, IllegalArgumentException {

    // A null start key means to start from the beginning of the table.
    // Cannot convert a null key to bytes.
    byte[] startKeyBytes = null;
    if (startKey != null) {
      startKeyBytes = codecRegistry.asRawData(startKey);
    }

    List<? extends KeyValue<byte[], byte[]>> rangeKVBytes =
            rawTable.getRangeKVs(startKeyBytes, count, filters);

    List<TypedKeyValue> rangeKVs = new ArrayList<>();
    rangeKVBytes.forEach(byteKV -> rangeKVs.add(new TypedKeyValue(byteKV)));

    return rangeKVs;
  }

  @Override
  public List<TypedKeyValue> getSequentialRangeKVs(
          KEY startKey, int count,
          MetadataKeyFilters.MetadataKeyFilter... filters)
          throws IOException, IllegalArgumentException {

    // A null start key means to start from the beginning of the table.
    // Cannot convert a null key to bytes.
    byte[] startKeyBytes = null;
    if (startKey != null) {
      startKeyBytes = codecRegistry.asRawData(startKey);
    }

    List<? extends KeyValue<byte[], byte[]>> rangeKVBytes =
            rawTable.getSequentialRangeKVs(startKeyBytes, count, filters);

    List<TypedKeyValue> rangeKVs = new ArrayList<>();
    rangeKVBytes.forEach(byteKV -> rangeKVs.add(new TypedKeyValue(byteKV)));

    return rangeKVs;
  }

  @Override
  public void cleanupCache(List<Long> epochs) {
    cache.cleanup(epochs);
  }

  @VisibleForTesting
  TableCache<CacheKey<KEY>, CacheValue<VALUE>> getCache() {
    return cache;
  }

  public Table<byte[], byte[]> getRawTable() {
    return rawTable;
  }

  public CodecRegistry getCodecRegistry() {
    return codecRegistry;
  }

  public Class<KEY> getKeyType() {
    return keyType;
  }

  public Class<VALUE> getValueType() {
    return valueType;
  }

  /**
   * Key value implementation for strongly typed tables.
   */
  public class TypedKeyValue implements KeyValue<KEY, VALUE> {

    private KeyValue<byte[], byte[]> rawKeyValue;

    public TypedKeyValue(KeyValue<byte[], byte[]> rawKeyValue) {
      this.rawKeyValue = rawKeyValue;
    }

    public TypedKeyValue(KeyValue<byte[], byte[]> rawKeyValue,
        Class<KEY> keyType, Class<VALUE> valueType) {
      this.rawKeyValue = rawKeyValue;
    }

    @Override
    public KEY getKey() throws IOException {
      return codecRegistry.asObject(rawKeyValue.getKey(), keyType);
    }

    @Override
    public VALUE getValue() throws IOException {
      return codecRegistry.asObject(rawKeyValue.getValue(), valueType);
    }
  }

  /**
   * Table Iterator implementation for strongly typed tables.
   */
  public class TypedTableIterator implements TableIterator<KEY, TypedKeyValue> {

    private TableIterator<byte[], ? extends KeyValue<byte[], byte[]>>
        rawIterator;
    private final Class<KEY> keyClass;
    private final Class<VALUE> valueClass;

    public TypedTableIterator(
        TableIterator<byte[], ? extends KeyValue<byte[], byte[]>> rawIterator,
        Class<KEY> keyType,
        Class<VALUE> valueType) {
      this.rawIterator = rawIterator;
      keyClass = keyType;
      valueClass = valueType;
    }

    @Override
    public void seekToFirst() {
      rawIterator.seekToFirst();
    }

    @Override
    public void seekToLast() {
      rawIterator.seekToLast();
    }

    @Override
    public TypedKeyValue seek(KEY key) throws IOException {
      byte[] keyBytes = codecRegistry.asRawData(key);
      KeyValue<byte[], byte[]> result = rawIterator.seek(keyBytes);
      if (result == null) {
        return null;
      }
      return new TypedKeyValue(result);
    }

    @Override
    public void close() throws IOException {
      rawIterator.close();
    }

    @Override
    public boolean hasNext() {
      return rawIterator.hasNext();
    }

    @Override
    public TypedKeyValue next() {
      return new TypedKeyValue(rawIterator.next(), keyType,
          valueType);
    }

    @Override
    public void removeFromDB() throws IOException {
      rawIterator.removeFromDB();
    }
  }
}
