package com.intellij.util.indexing;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.Alarm;
import com.intellij.util.CommonProcessors;
import com.intellij.util.Processor;
import com.intellij.util.io.PersistentHashMap;
import com.intellij.util.io.storage.HeavyProcessLatch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author Eugene Zhuravlev
 *         Date: Dec 10, 2007
 */
public class MapReduceIndex<Key, Value, Input> implements UpdatableIndex<Key,Value, Input> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.util.indexing.MapReduceIndex");
  private final ID<Key, Value> myIndexId;
  private final DataIndexer<Key, Value, Input> myIndexer;
  private final IndexStorage<Key, Value> myStorage;
  private PersistentHashMap<Integer, Collection<Key>> myInputsIndex;

  private final ReentrantReadWriteLock myLock = new ReentrantReadWriteLock();
  
  private final Alarm myFlushAlarm = new Alarm(Alarm.ThreadToUse.SHARED_THREAD);
  private final Runnable myFlushRequest = new Runnable() {
    public void run() {
      if (canFlush()) {
        try {
          flush();
        }
        catch (StorageException e) {
          LOG.info(e);
          if (myIndexId != null) {
            FileBasedIndex.getInstance().requestRebuild(myIndexId);
          }
        }
      }
      else {
        myFlushAlarm.addRequest(myFlushRequest, 20000 /* 20 sec */);  // postpone flushing
      }
    }

    private boolean canFlush() {
      if (HeavyProcessLatch.INSTANCE.isRunning()) {
        final Runtime runtime = Runtime.getRuntime();
        final float used = runtime.totalMemory() - runtime.freeMemory();
        final float memUsage = used / runtime.maxMemory();
        return memUsage >= 0.95f;
      }
      return true;
    }
  };


  public MapReduceIndex(@Nullable final ID<Key, Value> indexId, DataIndexer<Key, Value, Input> indexer, final IndexStorage<Key, Value> storage) {
    myIndexId = indexId;
    myIndexer = indexer;
    myStorage = storage;
  }

  public IndexStorage<Key, Value> getStorage() {
    return myStorage;
  }

  public void clear() throws StorageException {
    try {
      getWriteLock().lock();
      myStorage.clear();
    }
    catch (StorageException e) {
      LOG.error(e);
    }
    finally {
      getWriteLock().unlock();
    }
  }

  public void flush() throws StorageException{
    try {
      getReadLock().lock();
      myStorage.flush();
    }
    catch (IOException e) {
      throw new StorageException(e);
    }
    catch (RuntimeException e) {
      final Throwable cause = e.getCause();
      if (cause instanceof StorageException || cause instanceof IOException) {
        throw new StorageException(cause);
      }
      else {
        throw e;
      }
    }
    finally {
      getReadLock().unlock();
    }
  }

  public void dispose() {
    final Lock lock = getWriteLock();
    try {
      lock.lock();
      try {
        myStorage.close();
      }
      finally {
        if (myInputsIndex != null) {
          try {
            myInputsIndex.close();
          }
          catch (IOException e) {
            LOG.error(e);
          }
        }
      }
    }
    catch (StorageException e) {
      LOG.error(e);
    }
    finally {
      lock.unlock();
    }
  }

  public Lock getReadLock() {
    return myLock.readLock();
  }

  public Lock getWriteLock() {
    return myLock.writeLock();
  }

  public Collection<Key> getAllKeys() throws StorageException {
    Set<Key> allKeys = new HashSet<Key>();
    processAllKeys(new CommonProcessors.CollectProcessor<Key>(allKeys));
    return allKeys;
  }

  public boolean processAllKeys(Processor<Key> processor) throws StorageException {
    final Lock lock = getReadLock();
    try {
      lock.lock();
      return myStorage.processKeys(processor);
    }
    finally {
      lock.unlock();
    }
  }

  @NotNull
  public ValueContainer<Value> getData(final Key key) throws StorageException {
    final Lock lock = getReadLock();
    try {
      lock.lock();
      return myStorage.read(key);
    }
    finally {
      lock.unlock();
    }
  }

  public void removeData(final Key key) throws StorageException {
    final Lock lock = getWriteLock();
    try {
      lock.lock();
      myStorage.remove(key);
    }
    finally {
      lock.unlock();
      scheduleFlush();
    }
  }

  public void setInputIdToDataKeysIndex(PersistentHashMap<Integer, Collection<Key>> metaIndex) {
    myInputsIndex = metaIndex;
  }

  public final void update(final int inputId, @Nullable Input content) throws StorageException {
    //final Map<Key, Value> oldData = mapOld(oldContent);
    assert myInputsIndex != null;
    
    final Map<Key, Value> data = mapNew(content);

    Collection<Key> oldKeys;
    try {
      oldKeys = myInputsIndex.get(inputId);
    }
    catch (IOException e) {
      throw new StorageException(e);
    }
    if (oldKeys == null) {
      oldKeys = Collections.emptyList();
    }
    getWriteLock().lock();
    try {
      // remove outdated values
      updateWithMap(inputId, data, oldKeys);
      try {
        final Set<Key> newKeys = data.keySet();
        if (newKeys.size() > 0) {
          myInputsIndex.put(inputId, newKeys);
        }
        else {
          myInputsIndex.remove(inputId);
        }
      }
      catch (IOException e) {
        throw new StorageException(e);
      }
    }
    finally {
      getWriteLock().unlock();
    }
  }

  protected Map<Key, Value> mapNew(final Input content) throws StorageException {
    return content != null ? myIndexer.map(content) : Collections.<Key, Value>emptyMap();
  }

  protected void updateWithMap(final int inputId, final Map<Key, Value> newData, Collection<Key> oldKeys) throws StorageException {
    for (Key key : oldKeys) {
      myStorage.removeAllValues(key, inputId);
    }
    // add new values
    for (Key key : newData.keySet()) {
      final Value newValue = newData.get(key);
      myStorage.addValue(key, inputId, newValue);
    }
    scheduleFlush();
  }

  private void scheduleFlush() {
    myFlushAlarm.cancelAllRequests();
    myFlushAlarm.addRequest(myFlushRequest, 15000 /* 15 sec */);
  }

}
