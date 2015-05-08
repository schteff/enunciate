package com.webcohesion.enunciate;

import com.webcohesion.enunciate.module.DependencySpec;
import com.webcohesion.enunciate.module.DependingModuleAware;
import com.webcohesion.enunciate.module.EnunciateModule;
import com.webcohesion.enunciate.module.TypeFilteringModule;
import org.jgrapht.DirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.junit.Test;
import org.reflections.Reflections;
import org.reflections.adapters.MetadataAdapter;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.*;

/**
 * @author Ryan Heaton
 */
public class EnunciateTest {

  @Test
  public void testBuildModuleGraph() throws Exception {
    final Map<String, TestModule> myModules = new HashMap<String, TestModule>();
    List<String> moduleCallOrder = new ArrayList<String>();
    myModules.put("a", new TestModule("a", moduleCallOrder));
    myModules.put("b", new TestModule("b", moduleCallOrder));
    myModules.put("c", new TestModule("c", moduleCallOrder));
    myModules.put("d", new TestModule("d", moduleCallOrder, "a"));
    myModules.put("e", new TestModule("e", moduleCallOrder, "b", "c"));
    myModules.put("f", new TestModule("f", moduleCallOrder, "d", "e"));

    Enunciate enunciate = new Enunciate();
    DirectedGraph<String, DefaultEdge> graph = enunciate.buildModuleGraph(myModules);
    assertEquals(1, myModules.get("a").dependingModules.size());
    assertEquals("d", myModules.get("a").dependingModules.iterator().next());
    assertEquals(1, myModules.get("b").dependingModules.size());
    assertEquals("e", myModules.get("b").dependingModules.iterator().next());
    assertEquals(1, myModules.get("c").dependingModules.size());
    assertEquals("e", myModules.get("c").dependingModules.iterator().next());
    assertEquals(1, myModules.get("d").dependingModules.size());
    assertEquals("f", myModules.get("d").dependingModules.iterator().next());
    assertEquals(1, myModules.get("e").dependingModules.size());
    assertEquals("f", myModules.get("e").dependingModules.iterator().next());

    myModules.put("a", new TestModule("a", moduleCallOrder, "f")); //replace 'a' with a circular dependency.
    try {
      enunciate.buildModuleGraph(myModules);
      fail();
    }
    catch (EnunciateException e) {
      //fall through...
    }
  }

  @Test
  public void testCallOrder() throws Exception {
    final Map<String, TestModule> myModules = new HashMap<String, TestModule>();
    List<String> moduleCallOrder = Collections.synchronizedList(new ArrayList<String>());
    myModules.put("a", new TestModule("a", moduleCallOrder));
    myModules.put("b", new TestModule("b", moduleCallOrder));
    myModules.put("c", new TestModule("c", moduleCallOrder));
    myModules.put("d", new TestModule("d", moduleCallOrder, "a"));
    myModules.put("e", new TestModule("e", moduleCallOrder, "b", "c"));
    myModules.put("f", new TestModule("f", moduleCallOrder, "d", "e"));

    Enunciate enunciate = new Enunciate();
    enunciate.composeEngine(new EnunciateContext(null, null), myModules, enunciate.buildModuleGraph(myModules)).toBlocking().single();
    assertEquals(6, moduleCallOrder.size());

    assertTrue("'a' should be before 'd': " + moduleCallOrder, moduleCallOrder.indexOf("a") < moduleCallOrder.indexOf("d"));
    assertTrue("'b' should be before 'e': " + moduleCallOrder, moduleCallOrder.indexOf("b") < moduleCallOrder.indexOf("e"));
    assertTrue("'c' should be before 'e': " + moduleCallOrder, moduleCallOrder.indexOf("c") < moduleCallOrder.indexOf("e"));
    assertTrue("'d' should be before 'f': " + moduleCallOrder, moduleCallOrder.indexOf("d") < moduleCallOrder.indexOf("f"));
    assertTrue("'e' should be before 'f': " + moduleCallOrder, moduleCallOrder.indexOf("e") < moduleCallOrder.indexOf("f"));
  }

  @Test
  public void testClasspathScanning() throws Exception {
    Enunciate enunciate = new Enunciate();
    enunciate.setModules(Arrays.asList((EnunciateModule) new TestModule("test", new ArrayList<String>())));
    Reflections reflections = enunciate.loadApiReflections(buildTestClasspath());
    Set<String> scannedEntries = reflections.getStore().get(EnunciateReflectionsScanner.class.getSimpleName()).keySet();
    assertTrue(scannedEntries.contains("enunciate.Class1"));
    assertTrue(scannedEntries.contains("enunciate.Class2"));
    assertTrue(scannedEntries.contains("enunciate.Class3"));
    assertTrue(scannedEntries.contains("enunciate/Class1.java"));
    assertEquals(4, scannedEntries.size());
    assertFalse(scannedEntries.isEmpty());
  }

  private List<URL> buildTestClasspath() throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    Enunciate.URLFileObject source1 = new Enunciate.URLFileObject(getClass().getResource("/enunciate/Class1.java"));
    File outputDir1 = createTempDir();
    List<String> options = Arrays.asList("-d", outputDir1.getAbsolutePath() );
    assertTrue(compiler.getTask(null, null, null, options, null, Arrays.asList(source1)).call());
    File sourceFile1 = new File(new File(outputDir1, "enunciate"), "Class1.java");
    InputStream in = getClass().getResourceAsStream("/enunciate/Class1.java");
    OutputStream out = new FileOutputStream(sourceFile1);
    byte[] buffer = new byte[1024]; //buffer of 1K should be fine.
    int len;
    while ((len = in.read(buffer)) > 0) {
      out.write(buffer, 0, len);
    }
    in.close();
    out.close();


    Enunciate.URLFileObject source2 = new Enunciate.URLFileObject(getClass().getResource("/enunciate/Class2.java"));
    File outputDir2 = createTempDir();
    options = Arrays.asList("-d", outputDir2.getAbsolutePath() );
    assertTrue(compiler.getTask(null, null, null, options, null, Arrays.asList(source2)).call());

    Enunciate.URLFileObject source3 = new Enunciate.URLFileObject(getClass().getResource("/enunciate/Class3.java"));
    File outputDir3 = createTempDir();
    options = Arrays.asList("-d", outputDir3.getAbsolutePath() );
    assertTrue(compiler.getTask(null, null, null, options, null, Arrays.asList(source3)).call());

    File jar1 = File.createTempFile("EnunciateTest", ".jar");
    jar(jar1, outputDir1);

    File jar2 = File.createTempFile("EnunciateTest", ".jar");
    jar(jar2, outputDir2);

    return Arrays.asList(jar1.toURI().toURL(), jar2.toURI().toURL(), outputDir3.toURI().toURL());
  }

  public void jar(File toFile, File... dirs) throws IOException {
    if (!toFile.getParentFile().exists()) {
      toFile.getParentFile().mkdirs();
    }

    byte[] buffer = new byte[2 * 1024]; //buffer of 2K should be fine.
    JarOutputStream jarout = new JarOutputStream(new FileOutputStream(toFile));
    for (File dir : dirs) {

      URI baseURI = dir.toURI();
      ArrayList<File> files = new ArrayList<File>();
      buildFileList(files, dir);
      for (File file : files) {
        JarEntry entry = new JarEntry(baseURI.relativize(file.toURI()).getPath());
        jarout.putNextEntry(entry);

        if (!file.isDirectory()) {
          FileInputStream in = new FileInputStream(file);
          int len;
          while ((len = in.read(buffer)) > 0) {
            jarout.write(buffer, 0, len);
          }
          in.close();
        }

        // Complete the entry
        jarout.closeEntry();
      }
    }

    jarout.close();
  }

  protected void buildFileList(List<File> list, File... dirs) {
    for (File dir : dirs) {
      for (File file : dir.listFiles()) {
        if (file.isDirectory()) {
          buildFileList(list, file);
        }
        else {
          list.add(file);
        }
      }
    }
  }

  private File createTempDir() throws IOException {
    final Double random = Math.random() * 10000; //this random name is applied to avoid an "access denied" error on windows.
    final File tempDir = File.createTempFile("EnunciateTest" + random.intValue(), "");
    tempDir.delete();
    tempDir.mkdirs();
    return tempDir;
  }

  private class TestModule implements EnunciateModule, DependingModuleAware, DependencySpec, TypeFilteringModule {

    private final String name;
    private final Set<String> moduleDependencies;
    private Set<String> dependingModules;
    private final List<String> moduleCallOrder;
    private EnunciateContext context;

    private TestModule(String name, List<String> moduleCallOrder, String... moduleDependencies) {
      this.name = name;
      this.moduleCallOrder = moduleCallOrder;
      this.moduleDependencies = new TreeSet<String>(Arrays.asList(moduleDependencies));
    }

    @Override
    public void acknowledgeDependingModules(Set<String> dependingModules) {
      this.dependingModules = dependingModules;
    }

    @Override
    public String getName() {
      return this.name;
    }

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public List<DependencySpec> getDependencies() {
      return Arrays.asList((DependencySpec)this);
    }

    @Override
    public boolean accept(EnunciateModule module) {
      return this.moduleDependencies.contains(module.getName());
    }

    @Override
    public boolean isFulfilled() {
      return true;
    }

    @Override
    public void init(Enunciate engine) {

    }

    @Override
    public void init(EnunciateContext context) {
      this.context = context;
    }

    @Override
    public void call(EnunciateContext context) {
      this.moduleCallOrder.add(getName());
    }

    @Override
    public boolean acceptType(Object type, MetadataAdapter metadata) {
      return true;
    }
  }
}
