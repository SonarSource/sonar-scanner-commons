/*
 * SonarQube Scanner Commons
 * Copyright (C) 2011-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.scanner.api.internal;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.sonarsource.scanner.api.ZipUtils;
import org.sonarsource.scanner.api.internal.cache.FileCache;

import static java.lang.String.format;
import static org.sonarsource.scanner.api.Utils.deleteQuietly;

public class JreDownloader {

  private static final String JRE_INFO_FILENAME = "filename";
  private static final String JRE_INFO_CHECKSUM = "checksum";
  private static final String JRE_INFO_JAVA_PATH = "javaPath";
  private final ServerConnection serverConnection;
  private final FileCache fileCache;

  public JreDownloader(ServerConnection serverConnection, FileCache fileCache) {
    this.serverConnection = serverConnection;
    this.fileCache = fileCache;
  }

  public File download(OsArchProvider.OsArch osArch) {
    Map<String, String> jreInfo = getJreInfo(serverConnection, osArch);
    File cachedFile = fileCache.get(jreInfo.get(JRE_INFO_FILENAME), jreInfo.get(JRE_INFO_CHECKSUM),
      new JreArchiveDownloader(serverConnection));
    File unzipDirectory = unzipFile(cachedFile);
    return new File(unzipDirectory, jreInfo.get(JRE_INFO_JAVA_PATH));
  }

  private static Map<String, String> getJreInfo(ServerConnection serverConnection, OsArchProvider.OsArch osArch) {
    try {
      String jreInfoResponse = serverConnection.downloadString(
        String.format("/api/v2/scanner/jre/info?os=%s&arch=%s", osArch.getOs(), osArch.getArch()));
      Map<String, String> jreInfo = new HashMap<>();
      JsonObject jsonObject = Json.parse(jreInfoResponse).asObject();
      for (JsonObject.Member member : jsonObject) {
        jreInfo.put(member.getName(), member.getValue().asString());
      }
      return jreInfo;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to get JRE info", e);
    }
  }

  private static File unzipFile(File cachedFile) {
    String filename = cachedFile.getName();
    File destDir = new File(cachedFile.getParentFile(), filename + "_unzip");
    File lockFile = new File(cachedFile.getParentFile(), filename + "_unzip.lock");
    if (!destDir.exists()) {
      try (FileOutputStream out = new FileOutputStream(lockFile)) {
        FileLock lock = createLockWithRetries(out.getChannel());
        try {
          // Recheck in case of concurrent processes
          if (!destDir.exists()) {
            File tempDir = Files.createTempDirectory(cachedFile.getParentFile().toPath(), "jre").toFile();
            //TODO Handle other compression types
            ZipUtils.unzip(cachedFile, tempDir);
            Files.move(tempDir.toPath(), destDir.toPath());
          }
        } finally {
          lock.release();
        }
      } catch (IOException e) {
        throw new IllegalStateException("Failed to unzip file", e);
      } finally {
        deleteQuietly(lockFile.toPath());
      }
    }
    return destDir;
  }

  private static FileLock createLockWithRetries(FileChannel channel) throws IOException {
    int tryCount = 0;
    while (tryCount++ < 10) {
      try {
        return channel.lock();
      } catch (OverlappingFileLockException ofle) {
        // ignore overlapping file exception
      }
      try {
        Thread.sleep(200L * tryCount);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    throw new IOException("Unable to get lock after " + tryCount + " tries");
  }

  private static class JreArchiveDownloader implements FileCache.Downloader {
    private final ServerConnection connection;

    JreArchiveDownloader(ServerConnection connection) {
      this.connection = connection;
    }

    @Override
    public void download(String filename, File toFile) throws IOException {
      connection.downloadFile(format("/api/v2/scanner/jre/download?filename=%s", filename), toFile.toPath());
    }
  }
}
