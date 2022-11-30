import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;

public class Test {
    public static void main(String[] args) {
        Animal a = new Cat();
        Cat b = new Cat();
        System.out.println(a instanceof Cat); // true
        a.say();
        a = new Dog();
        a.say();
        a.run();
        // 1
        class Neg implements Function<Integer, Integer> {
            @Override
            public Integer apply(Integer n) {
                return -n;
            }
        }
        Function<Integer, Integer> neg = new Neg();
        // 2
        Function<Integer, Integer> neg0 = n -> -n;
        // 3
        Function<Integer, Integer> neg1 = new Function<>() {
            @Override
            public Integer apply(Integer integer) {
                return -integer;
            }
        };

        List<Test> list1 = new ArrayList<>();
        Collections.sort(list1, (o1, o2) -> 0);
        list1.sort(Comparator.comparingInt(o -> o.i));
        list1.sort(Comparator.comparingInt(Test::getX));
    }

    int i;

    private int getX() {
        return 0;
    }
}

interface Flyable {
    void fly();
}

abstract class Animal {
    public abstract void say();

    public void run() {
        System.out.println("run!");
    }
}

class Dog extends Animal {
    @Override
    public void say() {
        System.out.println("wolf");
    }

    @Override
    public void run() {
        System.out.println("run!!!");
    }

}

class Cat extends Animal {
    @Override
    public void say() {
        System.out.println("mi");
    }
}

class Bird extends Animal implements Flyable {
    @Override
    public void say() {
        System.out.println("mi");
    }

    @Override
    public void fly() {
        System.out.println("fly--");
    }
}


