package arc.record;

public class Tuple<T, E> {
    T t;
    E e;

    public Tuple(T t, E e) {
        this.t = t;
        this.e = e;
    }

    @Override
    public int hashCode() {
        return t.hashCode() + e.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) return false;
        Tuple<?, ?> tuple = (Tuple<?, ?>) o;
        return t.equals(tuple.t) && e.equals(tuple.e);
    }
}
