package fitnesseMain;

import fitnesse.components.ComponentFactory;
import fitnesse.FitNesse;
import fitnesse.FitNesseContext;
import fitnesse.FitNesseContext.Builder;
import fitnesse.Updater;
import fitnesse.components.PluginsClassLoader;
import fitnesse.PluginsLoader;
import fitnesse.wiki.RecentChanges;
import fitnesse.wiki.RecentChangesWikiPage;
import fitnesse.responders.WikiImportTestEventListener;
import fitnesse.reporting.TestTextFormatter;
import fitnesse.updates.UpdaterImplementation;
import fitnesse.wiki.fs.FileSystemPageFactory;
import fitnesse.wiki.WikiPageFactory;
import fitnesse.wiki.fs.VersionsController;
import fitnesse.wiki.fs.ZipFileVersionsController;
import fitnesse.wikitext.parser.SymbolProvider;
import util.CommandLine;

import java.io.*;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class FitNesseMain {
  private static final Logger LOG = Logger.getLogger(FitNesseMain.class.getName());

  private String extraOutput = "";

  public static void main(String[] args) throws Exception {
    Arguments arguments = parseCommandLine(args);
    if (arguments == null) {
      printUsage();
      exit(1);
    }
    Integer exitCode = new FitNesseMain().launchFitNesse(arguments);
    if (exitCode != null) {
      exit(exitCode);
    }
  }

  protected static void exit(int exitCode) {
    System.exit(exitCode);
  }

  public Integer launchFitNesse(Arguments arguments) throws Exception {
    configureLogging(arguments.hasVerboseLogging());
    loadPlugins();
    FitNesseContext context = loadContext(arguments);

    update(arguments, context);
    return launch(arguments, context);
  }

  boolean update(Arguments arguments, FitNesseContext context) throws IOException {
    if (!arguments.isOmittingUpdates()) {
      Updater updater = new UpdaterImplementation(context);
      return updater.update();
    }
    return false;
  }

  private void loadPlugins() throws Exception {
    new PluginsClassLoader().addPluginsToClassLoader();
  }

  Integer launch(Arguments arguments, FitNesseContext context) throws Exception {
    if (!arguments.isInstallOnly()) {
      boolean started = context.fitNesse.start();
      if (started) {
        if (arguments.getCommand() != null) {
          return executeSingleCommand(arguments, context);
        }
      }
    }
    return null;
  }

  private int executeSingleCommand(Arguments arguments, FitNesseContext context) throws Exception {
    TestTextFormatter.finalErrorCount = 0;
    LOG.info("Executing command: " + arguments.getCommand());

    OutputStream os;

    boolean outputRedirectedToFile = arguments.getOutput() != null;

    if (outputRedirectedToFile) {
      LOG.info("-----Command Output redirected to " + arguments.getOutput() + "-----");
      os = new FileOutputStream(arguments.getOutput());
    } else {
      LOG.info("-----Command Output-----");
      os = System.out;
    }

    context.fitNesse.executeSingleCommand(arguments.getCommand(), os);
    context.fitNesse.stop();

    if (outputRedirectedToFile) {
      os.close();
    } else {
      LOG.info("-----Command Complete-----");
    }

    return TestTextFormatter.finalErrorCount;
  }

  private FitNesseContext loadContext(Arguments arguments)
    throws Exception {
    Properties properties = loadConfigFile(arguments.getConfigFile());
    // Enrich properties with command line values:
    properties.setProperty(ComponentFactory.VERSIONS_CONTROLLER_DAYS, Integer.toString(arguments.getDaysTillVersionsExpire()));

    Builder builder = new Builder();
    ComponentFactory componentFactory = new ComponentFactory(properties);

    WikiPageFactory wikiPageFactory = (WikiPageFactory) componentFactory.createComponent(ComponentFactory.WIKI_PAGE_FACTORY_CLASS, FileSystemPageFactory.class);

    builder.properties = properties;
    builder.port = arguments.getPort();
    builder.rootPath = arguments.getRootPath();
    builder.rootDirectoryName = arguments.getRootDirectory();

    builder.versionsController = (VersionsController) componentFactory.createComponent(ComponentFactory.VERSIONS_CONTROLLER_CLASS, ZipFileVersionsController.class);
    builder.versionsController.setHistoryDepth(Integer.parseInt(properties.getProperty(ComponentFactory.VERSIONS_CONTROLLER_DAYS, "14")));
    builder.recentChanges = (RecentChanges) componentFactory.createComponent(ComponentFactory.RECENT_CHANGES_CLASS, RecentChangesWikiPage.class);

    builder.root = wikiPageFactory.makeRootPage(builder.rootPath,
            builder.rootDirectoryName);

    PluginsLoader pluginsLoader = new PluginsLoader(componentFactory);

    builder.logger = pluginsLoader.makeLogger(arguments.getLogDirectory());
    builder.authenticator = pluginsLoader.makeAuthenticator(arguments.getUserpass());

    FitNesseContext context = builder.createFitNesseContext();

    SymbolProvider symbolProvider = SymbolProvider.wikiParsingProvider;

    pluginsLoader.loadPlugins(context.responderFactory, symbolProvider);
    pluginsLoader.loadResponders(context.responderFactory);
    pluginsLoader.loadSymbolTypes(symbolProvider);
    pluginsLoader.loadContentFilter();
    pluginsLoader.loadSlimTables();
    pluginsLoader.loadCustomComparators();

    WikiImportTestEventListener.register();

    LOG.info("root page: " + context.root);
    LOG.info("logger: " + (context.logger == null ? "none" : context.logger.toString()));
    LOG.info("authenticator: " + context.authenticator);
    LOG.info("page factory: " + context.pageFactory);
    LOG.info("page theme: " + context.pageFactory.getTheme());
    LOG.info("Starting FitNesse on port: " + context.port);

    return context;
  }

  public Properties loadConfigFile(final String propertiesFile) {
    FileInputStream propertiesStream = null;
    Properties properties = new Properties();
    File configurationFile = new File(propertiesFile);
    try {
      propertiesStream = new FileInputStream(configurationFile);
    } catch (FileNotFoundException e) {
      try {
        LOG.info(String.format("No configuration file found (%s)", configurationFile.getCanonicalPath()));
      } catch (IOException e1) {
        LOG.info(String.format("No configuration file found (%s)", propertiesFile));
      }
    }

    if (propertiesStream != null) {
      try {
        properties.load(propertiesStream);
        propertiesStream.close();
      } catch (IOException e) {
        LOG.log(Level.WARNING, String.format("Error reading configuration: %s", e.getMessage()));
      }
    }

    return properties;
  }

  public static void configureLogging(boolean verbose) {
    if (System.getProperty("java.util.logging.config.class") != null ||
            System.getProperty("java.util.logging.config.file") != null) {
      // Do not reconfigure logging if explicitly set from the command line
      return;
    }

    InputStream in = FitNesseMain.class.getResourceAsStream((verbose ? "verbose-" : "") + "logging.properties");
    try {
      LogManager.getLogManager().readConfiguration(in);
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Log configuration failed", e);
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException e) {
          LOG.log(Level.SEVERE, "Unable to close Log configuration file", e);
        }
      }
    }
    LOG.finest("Configured verbose logging");
  }

  // Move to Arguments class.
  public static Arguments parseCommandLine(String[] args) {
    CommandLine commandLine = new CommandLine(
      "[-v][-p port][-d dir][-r root][-l logDir][-f config][-e days][-o][-i][-a userpass][-c command][-b output]");
    Arguments arguments = null;
    if (commandLine.parse(args)) {
      arguments = new Arguments();
      if (commandLine.hasOption("v"))
        arguments.setVerboseLogging(true);
      if (commandLine.hasOption("p"))
        arguments.setPort(commandLine.getOptionArgument("p", "port"));
      if (commandLine.hasOption("d"))
        arguments.setRootPath(commandLine.getOptionArgument("d", "dir"));
      if (commandLine.hasOption("r"))
        arguments.setRootDirectory(commandLine.getOptionArgument("r", "root"));
      if (commandLine.hasOption("l"))
        arguments.setLogDirectory(commandLine.getOptionArgument("l", "logDir"));
      if (commandLine.hasOption("e"))
        arguments.setDaysTillVersionsExpire(commandLine.getOptionArgument("e", "days"));
      if (commandLine.hasOption("a"))
        arguments.setUserpass(commandLine.getOptionArgument("a", "userpass"));
      if (commandLine.hasOption("c"))
        arguments.setCommand(commandLine.getOptionArgument("c", "command"));
      if (commandLine.hasOption("b"))
        arguments.setOutput(commandLine.getOptionArgument("b", "output"));
      if (commandLine.hasOption("f"))
        arguments.setConfigFile(commandLine.getOptionArgument("f", "config"));
      arguments.setOmitUpdates(commandLine.hasOption("o"));
      arguments.setInstallOnly(commandLine.hasOption("i"));
    }
    return arguments;
  }

  private static void printUsage() {
    System.err.println("Usage: java -jar fitnesse.jar [-vpdrleoab]");
    System.err.println("\t-p <port number> {" + FitNesseContext.DEFAULT_PORT + "}");
    System.err.println("\t-d <working directory> {" + Arguments.DEFAULT_PATH
      + "}");
    System.err.println("\t-r <page root directory> {" + Arguments.DEFAULT_ROOT
      + "}");
    System.err.println("\t-l <log directory> {no logging}");
    System.err.println("\t-f <config properties file> {" + Arguments.DEFAULT_CONFIG_FILE + "}");
    System.err.println("\t-e <days> {" + Arguments.DEFAULT_VERSION_DAYS
      + "} Number of days before page versions expire");
    System.err.println("\t-o omit updates");
    System.err
      .println("\t-a {user:pwd | user-file-name} enable authentication.");
    System.err.println("\t-i Install only, then quit.");
    System.err.println("\t-c <command> execute single command.");
    System.err.println("\t-b <filename> redirect command output.");
    System.err.println("\t-v {off} Verbose logging");
  }
}
