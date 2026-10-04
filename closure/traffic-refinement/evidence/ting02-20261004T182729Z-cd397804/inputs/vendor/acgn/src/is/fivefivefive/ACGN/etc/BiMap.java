package is.fivefivefive.ACGN.etc;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.List;


// Serializable extension of AlloyASG.DoubleMap
public class BiMap<K, V> implements Serializable {
   private Map<K, V> lmap;
   private Map<V, K> rmap;

   public BiMap() {
      this.lmap = new HashMap<K, V>();
      this.rmap = new HashMap<V, K>();
   }

   public BiMap(Map<K, V> lmap) {
      Iterator<K> var3 = lmap.keySet().iterator();

      while(var3.hasNext()) {
         K key = var3.next();
         this.rmap.put(lmap.get(key), key);
      }
   }
   public List<K> keys() {
      return new ArrayList<K>(this.lmap.keySet());
   }

   public List<V> values() {
      return new ArrayList<V>(this.rmap.keySet());
   }

   public V get(K key) {
      return this.lmap.get(key);
   }

   public void put(K key, V value) {
      if (key != null && value != null) {
         this.lmap.put(key, value);
         this.rmap.put(value, key);
      }
   }

   public K rget(V value) {
      return this.rmap.get(value);
   }

   public void remove(K key) {
      V value = this.lmap.get(key);
      this.lmap.remove(key);
      this.rmap.remove(value);
   }

   public void rremove(V value) {
      K key = this.rmap.get(value);
      this.lmap.remove(key);
      this.rmap.remove(value);
   }

   public int size() {
      return this.lmap.size();
   }

   public boolean containsKey(K key) {
      return this.lmap.containsKey(key);
   }

   public boolean containsValue(V value) {
      return this.rmap.containsKey(value);
   }
    public static <K, V> void writeToFile(String filename, BiMap<K, V> map) {
        // serialize the map object to a file
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filename)))
        {
            oos.writeObject(map);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void putAll(BiMap<K,V> another) {
        for (K key : another.keys()) {
            put(key, another.get(key));
        }
    }
}