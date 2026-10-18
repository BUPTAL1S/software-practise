package lab.sip.client;

/**
 * Fat JAR entry point for JavaFX application.
 * JavaFX 11+ refuses to launch if the main class extends javafx.application.Application
 * and is loaded directly from a non-modular classpath Fat JAR.
 * This standalone Launcher class delegates to MainApp without extending Application.
 */
public final class Launcher {

  private Launcher() {}

  public static void main(String[] args) {
    if (args != null && args.length > 0) {
      for (String arg : args) {
        if ("--probe-only".equals(arg) || "--version".equals(arg) || "--help".equals(arg)) {
          System.out.println("Java SIP Client Fat JAR probe OK (PJSIP 2.17 / JavaFX 17)");
          return;
        }
      }
    }
    MainApp.main(args);
  }
}
