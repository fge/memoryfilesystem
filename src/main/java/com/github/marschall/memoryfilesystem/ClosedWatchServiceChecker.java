package com.github.marschall.memoryfilesystem;

import java.nio.file.ClosedWatchServiceException;

final class ClosedWatchServiceChecker extends ClosedChecker {

  void check() {
    if (!this.open) {
      throw new ClosedWatchServiceException();
    }
  }

}
