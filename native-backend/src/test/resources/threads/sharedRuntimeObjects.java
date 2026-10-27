import java.util.zip.CRC32;

public class Main {
    public static void main(String[] args) throws Exception {
        final int threads = 8;
        final long[] crcs = new long[threads];
        final boolean[] classes = new boolean[threads];
        final int[] groups = new int[threads];
        final boolean[] properties = new boolean[threads];
        final byte[] data = "hello nabu".getBytes();
        Thread[] all = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int id = i;
            all[i] = new Thread(new Runnable() {
                public void run() {
                    // nobody has used CRC32, Class objects or the properties before: the first use races
                    CRC32 crc = new CRC32();
                    for (int k = 0; k < 2000; k++) {
                        crc.reset();
                        crc.update(data, 0, data.length);
                    }
                    crcs[id] = crc.getValue();
                    boolean same = true;
                    for (int k = 0; k < 2000; k++) {
                        same &= "x".getClass() == String.class;
                        same &= Integer.valueOf(1).getClass() == Integer.class;
                        same &= new Object().getClass() == Object.class;
                        same &= data.getClass() == byte[].class;
                    }
                    classes[id] = same;
                    for (int k = 0; k < 10; k++) {
                        ThreadGroup group = new ThreadGroup("group-" + id + "-" + k);
                        if (group.toString().contains("group-" + id + "-" + k)) {
                            groups[id]++;
                        }
                    }
                    properties[id] = System.getProperty("os.name") != null && System.getProperty("file.separator") != null;
                }
            });
        }
        for (Thread t : all) {
            t.start();
        }
        for (Thread t : all) {
            t.join();
        }
        CRC32 reference = new CRC32();
        reference.update(data, 0, data.length);
        boolean sameCrc = true;
        boolean sameClasses = true;
        boolean propertiesRead = true;
        int groupCount = 0;
        for (int i = 0; i < threads; i++) {
            sameCrc &= crcs[i] == reference.getValue();
            sameClasses &= classes[i];
            propertiesRead &= properties[i];
            groupCount += groups[i];
        }
        System.out.println("crc " + sameCrc);
        System.out.println("classes " + sameClasses);
        System.out.println("groups " + groupCount);
        System.out.println("properties " + propertiesRead);
    }
}
