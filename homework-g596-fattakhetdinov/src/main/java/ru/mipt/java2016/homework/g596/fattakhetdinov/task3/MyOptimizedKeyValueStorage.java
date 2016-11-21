package ru.mipt.java2016.homework.g596.fattakhetdinov.task3;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import ru.mipt.java2016.homework.base.task2.KeyValueStorage;
import ru.mipt.java2016.homework.g596.fattakhetdinov.task2.SerializationStrategy;

public class MyOptimizedKeyValueStorage<K, V> implements KeyValueStorage<K, V> {

    private Map<K, Long> keysOffsetsTable = new HashMap<>();
    private final Map<K, V> storageChanges = new TreeMap<>();
    private final Set<K> deleteChanges = new TreeSet<>();
    private LoadingCache<K, V> cacheTable =
            CacheBuilder.newBuilder().maximumSize(200).softValues().build(new CacheLoader<K, V>() {
                @Override
                public V load(K key) {
                    V result = null;
                    try {
                        result = loadValueFromFile(key);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (result == null) {
                        throw new RuntimeException();
                    }
                    return result;
                }
            });
    private File initFile;
    private RandomAccessFile keysFile;
    private RandomAccessFile valuesFile;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock writeLock = readWriteLock.writeLock();
    private final Lock readLock = readWriteLock.readLock();
    private SerializationStrategy<K> keySerializationStrategy;
    private SerializationStrategy<V> valueSerializationStrategy;
    private final String keysFileName = "keysFile.db";
    private final String valuesFileName = "valuesFile.db";
    private final String initFileName = "initKeyValueStorage.txt";

    private String currentStorageType; //Строка для проверки типа хранилища
    private boolean isClosed;

    public MyOptimizedKeyValueStorage(String path,
            SerializationStrategy<K> keySerializationStrategy,
            SerializationStrategy<V> valueSerializationStrategy) throws IOException {
        currentStorageType =
                keySerializationStrategy.getType() + " : " + valueSerializationStrategy.getType();
        isClosed = false;

        File directory = new File(path);
        //Проверяем переданный путь на корректность
        if (!directory.isDirectory() || !directory.exists()) {
            throw new RuntimeException("Path doesn't exist");
        }

        this.keySerializationStrategy = keySerializationStrategy;
        this.valueSerializationStrategy = valueSerializationStrategy;

        initFile = new File(path + File.separator + initFileName);

        if (initFile.exists()) {
            loadDataFromFiles(); //Если файлы существуют, подгружаем данные из них
        } else {
            createNewFiles();
        }
    }

    private void createNewFiles() throws IOException {
        File fileK = new File(keysFileName);
        File fileV = new File(valuesFileName);

        fileK.createNewFile();
        fileV.createNewFile();

        keysFile = new RandomAccessFile(fileK, "rw");
        valuesFile = new RandomAccessFile(fileV, "rw");
    }

    private void loadDataFromFiles() throws IOException {
        File fileK = new File(keysFileName);
        File fileV = new File(valuesFileName);

        if (!fileK.exists() || !fileV.exists()) {
            throw new IOException("Files don't exist");
        }

        keysFile = new RandomAccessFile(fileK, "rw");
        valuesFile = new RandomAccessFile(fileV, "rw");

        try (DataInputStream input = new DataInputStream(new FileInputStream(initFile))) {
            String fileStorageType = input.readUTF(); //Считываем проверочную строку
            if (!currentStorageType.equals(fileStorageType)) {
                throw new RuntimeException(
                        String.format("Storage contains: %s; expected: %s", fileStorageType,
                                currentStorageType));
            }
        } catch (IOException e) {
            throw new IOException("Can't read from file " + initFile.getPath(), e);
        }

        readOffsetsTable();
    }

    private void readOffsetsTable() throws IOException {
        String fileStorageType = keysFile.readUTF(); //Считываем проверочную строку
        if (!currentStorageType.equals(fileStorageType)) {
            throw new RuntimeException(
                    String.format("Storage contains: %s; expected: %s", fileStorageType,
                            currentStorageType));
        }

        int numKeys = keysFile.readInt();
        for (int i = 0; i < numKeys; i++) {
            K key = keySerializationStrategy.deserializeFromFile(keysFile);
            Long offset = keysFile.readLong();
            keysOffsetsTable.put(key, offset);
        }
    }

    private void checkForClosedDatabase() {
        //Если файл закрыли, и кто-то пытается сделать что-то с БД, то кидаем исключение
        if (isClosed) {
            throw new RuntimeException("Access to the closed file");
        }
    }

    private V loadValueFromFile(K key) throws IOException {
        Long offset = keysOffsetsTable.get(key);
        valuesFile.seek(offset);
        return valueSerializationStrategy.deserializeFromFile(valuesFile);
    }

    @Override
    public V read(K key) {
        writeLock.lock();
        V result;
        try {
            checkForClosedDatabase();
            if (exists(key)) {
                result = storageChanges.get(key);
                if (result == null) {
                    result = cacheTable.get(key);
                }
            } else {
                result = null;
            }
        } catch (Exception exception) {
            result = null;
        } finally {
            writeLock.unlock();
        }
        return result;
    }

    @Override
    public boolean exists(K key) {
        readLock.lock();
        boolean result;
        try {
            checkForClosedDatabase();
            result = keysOffsetsTable.containsKey(key);
        } finally {
            readLock.unlock();
        }
        checkForClosedDatabase();
        return result;
    }

    @Override
    public void write(K key, V value) {
        writeLock.lock();
        try {
            checkForClosedDatabase();
            keysOffsetsTable.put(key, valuesFile.length());
            valuesFile.seek(valuesFile.length());
            valueSerializationStrategy.serializeToFile(value, valuesFile);
            deleteChanges.remove(key);
            storageChanges.put(key, value);
            changesCheck();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            writeLock.unlock();
        }

    }

    @Override
    public void delete(K key) {
        writeLock.lock();
        try {
            checkForClosedDatabase();
            if (exists(key)) {
                storageChanges.remove(key);
                deleteChanges.add(key);
                cacheTable.invalidate(key);
                keysOffsetsTable.remove(key);
                changesCheck();
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Iterator<K> readKeys() {
        Iterator<K> iterator;
        readLock.lock();
        try {
            checkForClosedDatabase();
            iterator = keysOffsetsTable.keySet().iterator();
        } finally {
            readLock.unlock();
        }
        return iterator;
    }

    @Override
    public int size() {
        int result;
        readLock.lock();
        try {
            checkForClosedDatabase();
            result = keysOffsetsTable.size();
        } finally {
            readLock.unlock();
        }
        return result;
    }

    private void changesCheck() {
        if (storageChanges.size() > 1000 || deleteChanges.size() > 2000) {
            storageChanges.clear();
            deleteChanges.clear();
            cacheTable.cleanUp();
        }


    }

    @Override
    public void close() throws IOException {
        DataOutputStream output = new DataOutputStream(new FileOutputStream(initFile));
        output.writeUTF(currentStorageType);
        output.close();

        keysFile.seek(0);
        keysFile.writeUTF(currentStorageType);
        keysFile.writeInt(keysOffsetsTable.size());
        for (K key : keysOffsetsTable.keySet()) {
            keySerializationStrategy.serializeToFile(key, keysFile);
            keysFile.writeLong(keysOffsetsTable.get(key));
        }
        keysOffsetsTable.clear();

        keysFile.close();
        valuesFile.close();
        isClosed = true;
    }
}