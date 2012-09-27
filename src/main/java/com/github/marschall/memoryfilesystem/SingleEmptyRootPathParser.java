package com.github.marschall.memoryfilesystem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class SingleEmptyRootPathParser implements PathParser {


  @Override
  public Path parse(Iterable<Root> roots, String first, String... more) {
    // REVIEW implement #count() to correctly set initial size
    // TODO empty path could be singleton
    List<String> elements = new ArrayList<>();
    this.parseInto(first, elements);
    if (more != null && more.length > 0) {
      for (String s : more) {
        this.parseInto(s, elements);
      }
    }
    Root root = roots.iterator().next();
    MemoryFileSystem memoryFileSystem = root.getMemoryFileSystem();
    if (this.isAbsolute(first, more)) {
      return AbstractPath.createAboslute(memoryFileSystem, root, elements);
    } else {
      return AbsolutePath.createRealative(memoryFileSystem, elements);
    }
  }
  
  private boolean isAbsolute(String first, String... more) {
    if (!first.isEmpty()) {
      return first.charAt(0) == '/';
    }
    if (more != null && more.length > 0) {
      for (String s : more) {
        if (!s.isEmpty()) {
          return s.charAt(0) == '/';
        }
      }
    }
    
    // only empty strings
    return false;
  }
  
  private void parseInto(String s, List<String> elements) {
    if (s.isEmpty()) {
      return;
    }
    
    int fromIndex = 0;
    int slashIndex = s.indexOf('/', fromIndex);
    while (slashIndex != -1) {
      if (slashIndex > fromIndex) {
        // avoid empty strings for things like //
        elements.add(s.substring(fromIndex, slashIndex));
      }
      
      fromIndex = slashIndex + 1;
      slashIndex  = s.indexOf('/', fromIndex);
    }
    if (fromIndex < s.length()) {
      elements.add(s.substring(fromIndex));
    }
    
  }

}