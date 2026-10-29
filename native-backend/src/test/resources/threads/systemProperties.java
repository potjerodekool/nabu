import java.util.Properties;

public class Main {
    public static void main(String[] args) throws Exception {
        System.setProperty("nabu.p.a", "1");
        System.out.println("clear " + System.clearProperty("nabu.p.a") + " " + System.clearProperty("nabu.p.a"));
        System.out.println("after " + System.getProperty("nabu.p.a") + " " + System.getProperties().getProperty("nabu.p.a"));

        // a change of the Properties object is a change of the system properties
        System.getProperties().setProperty("nabu.p.b", "2");
        System.out.println("viaProps " + System.getProperty("nabu.p.b"));
        System.getProperties().remove("nabu.p.b");
        System.out.println("removed " + System.getProperty("nabu.p.b"));

        Properties mine = new Properties();
        mine.setProperty("nabu.p.c", "3");
        Properties before = System.getProperties();
        System.setProperties(mine);
        System.out.println("replaced " + System.getProperty("nabu.p.c") + " " + (System.getProperties() == mine)
                + " " + System.getProperty("os.name"));
        System.setProperty("nabu.p.d", "4");
        System.out.println("inMine " + mine.getProperty("nabu.p.d"));

        System.setProperties(null);
        System.out.println("reset " + System.getProperty("nabu.p.c") + " " + (System.getProperty("os.name") != null)
                + " " + (System.getProperties() != mine));
    }
}
