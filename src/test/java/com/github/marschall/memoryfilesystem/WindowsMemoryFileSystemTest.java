package com.github.marschall.memoryfilesystem;

import static com.github.marschall.memoryfilesystem.IsHiddenMatcher.isHidden;
import static java.nio.file.AccessMode.EXECUTE;
import static java.nio.file.AccessMode.READ;
import static java.nio.file.AccessMode.WRITE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;

public class WindowsMemoryFileSystemTest {

  @Rule
  public final WindowsFileSystemRule rule = new WindowsFileSystemRule();

  @Test
  public void setAttributes() throws IOException, ParseException {
    FileSystem fileSystem = this.rule.getFileSystem();

    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    FileTime lastModifiedTime = FileTime.fromMillis(format.parse("2012-11-07T20:30:22").getTime());

    FileAttribute<?> hiddenAttribute = new StubFileAttribute<>("dos:hidden", true);

    Path hiddenPath = fileSystem.getPath("hidden");
    Files.createFile(hiddenPath, hiddenAttribute);
    DosFileAttributeView dosAttributeView = Files.getFileAttributeView(hiddenPath, DosFileAttributeView.class);
    assertTrue(dosAttributeView.readAttributes().isHidden());
  }

  @Test
  public void readAttributes() throws IOException, ParseException {
    FileSystem fileSystem = this.rule.getFileSystem();
    Path path = fileSystem.getPath("C:\\file.txt");

    Files.createFile(path);

    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    FileTime lastModifiedTime = FileTime.fromMillis(format.parse("2012-11-07T20:30:22").getTime());
    FileTime lastAccessTime = FileTime.fromMillis(format.parse("2012-10-07T20:30:22").getTime());
    FileTime createTime = FileTime.fromMillis(format.parse("2012-09-07T20:30:22").getTime());

    BasicFileAttributeView basicFileAttributeView = Files.getFileAttributeView(path, BasicFileAttributeView.class);
    basicFileAttributeView.setTimes(lastModifiedTime, lastAccessTime, createTime);
    DosFileAttributeView dosFileAttributeView = Files.getFileAttributeView(path, DosFileAttributeView.class);
    dosFileAttributeView.setHidden(true);

    Map<String, Object> attributes = Files.readAttributes(path, "dos:lastModifiedTime,lastAccessTime,size,hidden");

    Map<String, Object> expected = new HashMap<String, Object>(4);
    expected.put("size", 0L);
    expected.put("lastModifiedTime", lastModifiedTime);
    expected.put("lastAccessTime", lastAccessTime);
    expected.put("hidden", true);

    assertEquals(expected, attributes);
  }

  @Test
  public void windows() throws IOException {
    FileSystem fileSystem = this.rule.getFileSystem();
    Path c1 = fileSystem.getPath("C:\\");
    Path c2 = fileSystem.getPath("c:\\");
    assertEquals("C:\\", c1.toString());
    assertEquals(c1.hashCode(), c2.hashCode());
    assertTrue(c1.startsWith(c2));
    assertTrue(c1.startsWith("c:\\"));
    assertEquals(c1, c2);

    c1 = fileSystem.getPath("C:\\TEMP");
    c2 = fileSystem.getPath("c:\\temp");
    assertEquals("C:\\TEMP", c1.toString());
    assertTrue(c1.startsWith(c2));
    assertTrue(c1.startsWith("c:\\"));
  }

  @Test
  public void windowsDiffrentFileSystems() throws IOException {
    URI uri1 = URI.create("memory:uri1");
    URI uri2 = URI.create("memory:uri2");
    Map<String, ?> env = MemoryFileSystemBuilder.newWindows().buildEnvironment();
    try (
            FileSystem fileSystem1 = FileSystems.newFileSystem(uri1, env);
            FileSystem fileSystem2 = FileSystems.newFileSystem(uri2, env)) {
      Path c1 = fileSystem1.getPath("C:\\");
      Path c2 = fileSystem2.getPath("C:\\\\");

      assertThat(c1, equalTo(c1));
      assertThat(c2, equalTo(c2));

      // different file systems
      assertThat(c1, not(equalTo(c2)));
      assertThat(c2, not(equalTo(c1)));
    }
  }



  @Test
  public void forbiddenCharacters() throws IOException {
    FileSystem fileSystem = this.rule.getFileSystem();
    for (char c : "\\/:?\"<>|".toCharArray()) {
      try {
        fileSystem.getPath(Character.toString(c) + ".txt");
        fail(c + " should be forbidden");
      } catch (InvalidPathException e) {
        // should reach here
      }
    }
  }

  @Test
  public void windowsQuirky() throws IOException {
    FileSystem fileSystem = this.rule.getFileSystem();
    Path c1 = fileSystem.getPath("C:\\");
    Path c2 = fileSystem.getPath("c:\\");
    assertEquals("c:\\", c2.toString());

    c1 = fileSystem.getPath("C:\\TEMP");
    c2 = fileSystem.getPath("c:\\temp");
    assertEquals("c:\\temp", c2.toString());
    assertEquals(c1.hashCode(), c2.hashCode());
  }

  @Test
  public void checkAccess() throws IOException {
    FileSystem fileSystem = this.rule.getFileSystem();
    Path file = fileSystem.getPath("file.txt");

    Files.createFile(file);
    DosFileAttributeView attributeView = Files.getFileAttributeView(file, DosFileAttributeView.class);
    DosFileAttributes attributes = attributeView.readAttributes();
    assertFalse("is read only", attributes.isReadOnly());

    FileSystemProvider provider = file.getFileSystem().provider();
    provider.checkAccess(file, READ);
    provider.checkAccess(file, WRITE);
    provider.checkAccess(file, EXECUTE);

    attributeView.setReadOnly(true);
    provider.checkAccess(file, READ);
    provider.checkAccess(file, EXECUTE);

    try {
      provider.checkAccess(file, WRITE);
      fail("write should not be permitted");
    } catch (AccessDeniedException e) {
      // should reach here
    }

  }

  @Test
  public void copyAttributes() throws IOException {
    FileSystem fileSystem = this.rule.getFileSystem();
    Path source = fileSystem.getPath("source.txt");
    Path target = fileSystem.getPath("target.txt");

    Files.createFile(source);

    DosFileAttributeView sourceDosFileAttributeView = Files.getFileAttributeView(source, DosFileAttributeView.class);
    DosFileAttributes sourceDosAttributes = sourceDosFileAttributeView.readAttributes();
    assertFalse(sourceDosAttributes.isArchive());
    assertFalse(sourceDosAttributes.isHidden());
    assertFalse(sourceDosAttributes.isReadOnly());
    assertFalse(sourceDosAttributes.isSystem());

    sourceDosFileAttributeView.setArchive(true);
    sourceDosFileAttributeView.setHidden(true);
    sourceDosFileAttributeView.setReadOnly(true);
    sourceDosFileAttributeView.setSystem(true);

    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);

    DosFileAttributeView targetDosFileAttributeView = Files.getFileAttributeView(target, DosFileAttributeView.class);
    DosFileAttributes targetDosAttributes = targetDosFileAttributeView.readAttributes();
    assertTrue(targetDosAttributes.isArchive());
    assertTrue(targetDosAttributes.isHidden());
    assertTrue(targetDosAttributes.isReadOnly());
    assertTrue(targetDosAttributes.isSystem());
  }

  @Test
  public void dontCopyAttributes() throws IOException {
    FileSystem fileSystem = this.rule.getFileSystem();
    Path source = fileSystem.getPath("source.txt");
    Path target = fileSystem.getPath("target.txt");

    Files.createFile(source);

    DosFileAttributeView sourceDosFileAttributeView = Files.getFileAttributeView(source, DosFileAttributeView.class);
    DosFileAttributes sourceDosAttributes = sourceDosFileAttributeView.readAttributes();
    assertFalse(sourceDosAttributes.isArchive());
    assertFalse(sourceDosAttributes.isHidden());
    assertFalse(sourceDosAttributes.isReadOnly());
    assertFalse(sourceDosAttributes.isSystem());

    sourceDosFileAttributeView.setArchive(true);
    sourceDosFileAttributeView.setHidden(true);
    sourceDosFileAttributeView.setReadOnly(true);
    sourceDosFileAttributeView.setSystem(true);

    Files.copy(source, target);

    DosFileAttributeView targetDosFileAttributeView = Files.getFileAttributeView(target, DosFileAttributeView.class);
    DosFileAttributes targetDosAttributes = targetDosFileAttributeView.readAttributes();
    assertFalse(targetDosAttributes.isArchive());
    assertFalse(targetDosAttributes.isHidden());
    assertFalse(targetDosAttributes.isReadOnly());
    assertFalse(targetDosAttributes.isSystem());
  }



  @Test
  public void isHiddenPathResolution() throws IOException {
    FileSystem fileSystem = this.rule.getFileSystem();

    Path hidden = fileSystem.getPath("hidden.txt");
    Files.createFile(hidden);

    DosFileAttributeView attributeView = Files.getFileAttributeView(hidden, DosFileAttributeView.class);
    attributeView.setHidden(true);

    assertThat(hidden, isHidden());

    hidden = fileSystem.getPath("hidden.txt/.././hidden.txt");
    assertThat(hidden, isHidden());
  }

  @Test
  public void pathOrdering() throws IOException {
    FileSystem fileSystem = this.rule.getFileSystem();
    Path lowerA = fileSystem.getPath("a");
    Path upperA = fileSystem.getPath("A");

    assertEquals(0, lowerA.compareTo(lowerA));
    assertEquals(0, upperA.compareTo(lowerA));
    assertEquals(0, lowerA.compareTo(upperA));
    assertEquals(0, upperA.compareTo(upperA));

    assertEquals(lowerA, lowerA);
    assertEquals(lowerA, upperA);
    assertEquals(upperA, lowerA);
    assertEquals(upperA, upperA);

    Path c = fileSystem.getPath("C:\\");
    Path d = fileSystem.getPath("D:\\");
    assertThat(c, lessThan(d));
    assertThat(d, greaterThan(c));
  }

}
