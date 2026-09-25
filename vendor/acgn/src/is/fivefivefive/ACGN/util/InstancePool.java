package is.fivefivefive.ACGN.util;

import java.util.HashMap;
import java.util.Map;

import edu.mit.csail.sdg.translator.A4Solution;

/**
 * InstancePool is the LFU (Least Frequently Used) cache for instances.
 * It stores instances of the oracle solutions and provides methods to retrieve them.
 * For each time there is a predicate generated that matches all instances in the pool, 
 * if the new predicate is not an equivalent of the oracle solution,
 * and if the pool is full, the least frequently un-matched instance will be removed.
 */
public class InstancePool {
    private static class AlloyInstance {    
        public A4Solution instance;
        public AlloyInstance next;
        public AlloyInstance last;
        public int usageFrequency = 0; // for LFU cache
        public AlloyInstance(A4Solution instance) {
            this.instance = instance;
            this.next = null;
            this.last = null;
            this.usageFrequency = 1; // initial usage frequency
        }
    }
    private static class DoublyLinkedList {
        private AlloyInstance head;
        private AlloyInstance tail;
        private int size;
        public DoublyLinkedList() {
            this.head = null;
            this.tail = null;
            this.size = 0;
        }
        public void add(AlloyInstance instance) {
            if (head == null) {
                head = instance;
                tail = instance;
            } else {
                tail.next = instance;
                instance.last = tail;
                tail = instance;
            }
            size++;
        }
        public void remove(AlloyInstance instance) {
            if (instance == head) {
                head = instance.next;
                if (head != null) {
                    head.last = null;
                }
            } else if (instance == tail) {
                tail = instance.last;
                if (tail != null) {
                    tail.next = null;
                }
            } else {
                instance.last.next = instance.next;
                if (instance.next != null) {
                    instance.next.last = instance.last;
                }
            }
            size--;
        }
        public AlloyInstance removeLast() { 
            if (tail == null) {
                return null;
            }
            AlloyInstance lastInstance = tail;
            remove(lastInstance);
            return lastInstance;
        }
        public int size() {
            return size;
        }
        public boolean isEmpty() {
            return size == 0;
        }
    }
    private AlloyInstance head;
    // private AlloyInstance tail;
    private int size;
    private final int capacity;
    private final Map<Integer, DoublyLinkedList> frequencyMap; // Map from usage frequency to list of instances with that frequency
    private final Map<Integer, AlloyInstance> instanceMap; // Map from instance key to the actual instance for O(1) access
    private final Map<A4Solution, Integer> uniqueKey; // Map of the keys
    private int minFrequency; // Track the minimum frequency of instances in the pool
    private int gid = 0;
    public InstancePool(int capacity) {
        this.capacity = capacity;
        this.size = 0;
        this.head = null;
        // this.tail = null;
        this.frequencyMap = new HashMap<>();
        this.instanceMap = new HashMap<>();
        this.uniqueKey = new HashMap<>();
    }
    // must be O(1)
    public void add(A4Solution instance) {
        if (!uniqueKey.containsKey(instance)) {
            uniqueKey.put(instance, gid);
            gid++;
        }
        if (instanceMap.containsKey(uniqueKey.get(instance))) {
            // If the instance already exists, increment its usage frequency
            incrementUsageFrequency(instance);
        } else {
            if (size == capacity) {
                removeLeastFrequentlyUsed();
            }
            AlloyInstance newInstance = new AlloyInstance(instance);
            instanceMap.put(uniqueKey.get(instance), newInstance); // add to instance map
            frequencyMap.computeIfAbsent(1, k -> new DoublyLinkedList()).add(newInstance); // add to frequency map
            size++;
            if (size == 1) {
                head = newInstance; // set head if this is the first instance
            }
            minFrequency = 1; // reset minimum frequency to 1 for the new instance
        }
    }
    // must be O(1); no iteration over the list; LFU; take the new Map
    public A4Solution get(int index) {
        if (instanceMap.containsKey(index)) {
            AlloyInstance instance = instanceMap.get(index);
            instance.usageFrequency++;
            int oldFrequency = instance.usageFrequency - 1;
            frequencyMap.get(oldFrequency).remove(instance);
            frequencyMap.computeIfAbsent(instance.usageFrequency, k -> new DoublyLinkedList()).add(instance);
            if (frequencyMap.get(oldFrequency).isEmpty() && oldFrequency == minFrequency) {
                minFrequency++;
            }
            return instance.instance;
        }
        return null; // instance not found
    }
    public void incrementUsageFrequency(A4Solution instance) {
        Integer key = uniqueKey.get(instance);
        if (key != null) {
            get(key); // this will automatically increment the usage frequency
        }
    }
    public void removeLeastFrequentlyUsed() {
        if (size == 0) {
            return;
        }
        DoublyLinkedList leastFrequentList = frequencyMap.get(minFrequency);
        if (leastFrequentList != null && !leastFrequentList.isEmpty()) {
            AlloyInstance leastFrequentInstance = leastFrequentList.removeLast(); // remove the least frequently used instance
            instanceMap.remove(uniqueKey.get(leastFrequentInstance.instance)); // remove from instance map
            size--;
            if (size == 0) {
                head = null; // reset head if the pool is now empty
            }
        }
    }
    public int size() {
        return size;
    }
    public boolean isEmpty() {
        return size == 0;
    }
    public void clear() {
        head = null;
        // tail = null;
        size = 0;
    }
    public int getCapacity() {
        return capacity;
    }
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("InstancePool: [");
        AlloyInstance current = head;
        while (current != null) {
            sb.append(current.instance.toString()).append(", ");
            current = current.next;
        }
        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2); // remove last comma and space
        }
        sb.append("]");
        return sb.toString();
    }
    public boolean contains(A4Solution instance) {
        AlloyInstance current = head;
        while (current != null) {
            if (current.instance.equals(instance)) {
                return true;
            }
            current = current.next;
        }
        return false;
    }
    public void remove(A4Solution instance) {
        if (instanceMap.containsKey(uniqueKey.get(instance))) {
            AlloyInstance existingInstance = instanceMap.get(uniqueKey.get(instance));
            int frequency = existingInstance.usageFrequency;
            frequencyMap.get(frequency).remove(existingInstance);
            instanceMap.remove(uniqueKey.get(instance));
            size--;
        }
    }
    public boolean isFull() {
        return size == capacity;
    }
    public A4Solution getHead() {
        if (head == null) {
            return null;
        }
        return head.instance;
    }
    public int getUsageFrequency(A4Solution instance) {
        if (instanceMap.containsKey(uniqueKey.get(instance))) {
            return instanceMap.get(uniqueKey.get(instance)).usageFrequency;
        }
        return -1; // instance not found
    }
}
