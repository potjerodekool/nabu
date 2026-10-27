import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Exchanger;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

public class Main {
    static class Item implements Delayed {
        final String name;
        final long at;

        Item(String name, long delayMs) {
            this.name = name;
            this.at = System.nanoTime() + delayMs * 1000000L;
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(at - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        public int compareTo(Delayed other) {
            return Long.compare(at, ((Item) other).at);
        }
    }

    public static void main(String[] args) throws Exception {
        final Phaser phaser = new Phaser(2);
        Thread p = new Thread(() -> {
            phaser.arriveAndAwaitAdvance();
        });
        p.start();
        phaser.arriveAndAwaitAdvance();
        p.join();
        System.out.println("phaser " + phaser.getPhase());

        final Exchanger<String> exchanger = new Exchanger<String>();
        final String[] got = new String[1];
        Thread e = new Thread(() -> {
            try {
                got[0] = exchanger.exchange("from-thread");
            } catch (InterruptedException ex) {
            }
        });
        e.start();
        String mine = exchanger.exchange("from-main");
        e.join();
        System.out.println("exchanged " + got[0]);
        System.out.println("exchanged " + mine);

        DelayQueue<Item> queue = new DelayQueue<Item>();
        queue.put(new Item("second", 40));
        queue.put(new Item("first", 10));
        System.out.println("delayed " + queue.take().name);
        System.out.println("delayed " + queue.take().name);
    }
}
