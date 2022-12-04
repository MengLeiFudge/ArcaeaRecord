//import java.util.LinkedList;

public class LinkedListTest {
    public static void main(String[] args) {
        Integer[] nums = new Integer[]{1, 5, 2, 5, 5, 3, 5};
        LinkedList<Integer> list = new LinkedList<>(nums);
        System.out.println(list.toString());  // [1, 5, 2, 5, 5, 3, 5]
        list.remove(1);
        System.out.println(list.toString());  // [5, 2, 5, 5, 3, 5]
        list.removeAll(5);
        System.out.println(list.toString());  // [2, 3]
    }
}

class LinkedList<T> {
    Node head;
    int size;
    //T last;

    LinkedList() {
        head = null;
        size = 0;
    }

    LinkedList(T[] data) {
        this();
        int now = data.length - 1;
        while (now >= 0) {
            addHead(data[now]);
            now--;
        }
    }

    private class Node {
        T data;
        Node next;

        Node() {
            data = null;
            next = null;
        }

        Node(T data) {
            this.data = data;
            next = null;
        }

        public String toString() {
            return data.toString();
        }
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void addHead(T t) {
        Node newHead = new Node(t);
        newHead.next = head;
        head = newHead;
        size++;
    }

    public void removeHead() {
        head = head.next;
        size--;
    }

    public Node find(T t) {
        Node now = head;
        while (now != null) {
            if (now.data.equals(t)) {
                return now;
            }
            now = now.next;
        }
        return null;
    }

    public boolean remove(T t) {
        if (isEmpty()) return false;
        Node now = head;
        if (now.data.equals(t)) {
            removeHead();
            return true;
        }
        while (now.next != null) {
            if (now.next.data.equals(t)) {
                now.next = now.next.next;
                size--;
                return true;
            }
            now = now.next;
        }
        return false;
    }

    public boolean removeAll(T t) {
        if (isEmpty()) return false;
        boolean nodeRemoved = false;
        Node now = head;
        Node pre = new Node();
        pre.next = head;
        while (now != null) {
            if (now.data.equals(t)) {
                if (now == head) head = head.next;
                pre.next = pre.next.next;
                now = now.next;
                size--;
                nodeRemoved = true;
            } else {
                pre = now;
                now = now.next;
            }
        }
        return nodeRemoved;
    }

    public String toString() {
        if (isEmpty()) return "[]";
        String ret = "[";
        ret += head.toString();
        Node now = head.next;
        while (now != null) {
            ret += ", " + now.data.toString();
            now = now.next;
        }
        return ret + "]";
    }

}
