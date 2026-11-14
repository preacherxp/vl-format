package demo.edge;

import java.util.function.*;

@SuppressWarnings("unchecked")
public class Edge<T extends Comparable<T>> {
    private static final String BANNER = """
            welcome to "edge"
            """;

    private T[] items;
    private int count = 0;

    @SafeVarargs
    public Edge(T... initial) {
        items = initial;
        count = initial.length;
    }

    public T max() {
        T best = items[0];
        for (int i = 1; i<count; i++) {
            if (items[i].compareTo(best)>0) { best = items[i]; }
        }
        return best;
    }

    public void repeat(Runnable r, int times) {
        int i = 0;
        do {
            r.run();
            i++;
        } while (i<times);
    }

    public String kind() {
        return count == 0 ? "empty" : count == 1 ? "single" : "many";
    }
}
