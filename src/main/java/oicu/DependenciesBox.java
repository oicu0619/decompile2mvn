package oicu;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
public class DependenciesBox {
    // consumer, producer
    // threads will get something away, do something, and return it or destroy it.
    // example : takes one dep and process, later decide to add the dep to ask queue.
    // or take one dep, and later process it properly.
    // either way, must tell the box that it is done with the dep.
    // big lock is better ? afraid of coherency gurrantee.
    private final Object lock = new Object();
    private final Set<Dependency> processingDependencies = new HashSet<>();
    private final Set<Dependency> askDependencies = new HashSet<>();
    private int unsettledDependencies = 0;

    public void addprocessing(Dependency dep) {
        synchronized (lock) {
            processingDependencies.add(dep);
        }
    }

    public int processingSize(){
        synchronized (lock) {
            return processingDependencies.size();
        }
    }
    public void addask(Dependency dep) {
        synchronized (lock) {
            askDependencies.add(dep);
        }
    }

    public Dependency fetchProcessing() {
        synchronized (lock) {
            Iterator<Dependency> iterator = processingDependencies.iterator();
            if(iterator.hasNext()){
                Dependency dependency = iterator.next();
                iterator.remove();
                unsettledDependencies +=1;
                return dependency;
            } else {
                return null;
            }
        }
    }
    
    public Dependency fetchAsk() {
        synchronized (lock) {
            Iterator<Dependency> iterator = askDependencies.iterator();
            if(iterator.hasNext()){
                Dependency dependency = iterator.next();
                iterator.remove();
                unsettledDependencies +=1;
                return dependency;    
            } else {
                return null;
            }
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return processingDependencies.isEmpty() && askDependencies.isEmpty() && unsettledDependencies == 0;
        }
    }

    public void settleOneDependency() {
        synchronized (lock) {
            unsettledDependencies -= 1;
        }
    }
    
    public void moveAskToProcessing() {
        synchronized (lock) {
            processingDependencies.addAll(askDependencies);
            askDependencies.clear();
        }
    }
}