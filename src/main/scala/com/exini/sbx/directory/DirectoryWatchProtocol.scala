/*
 * Copyright 2019 EXINI Diagnostics
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exini.sbx.directory

import com.exini.sbx.model.Entity
import java.nio.file.Path

object DirectoryWatchProtocol {

  case class WatchedDirectory(id: Long, name: String, path: String) extends Entity

    
  sealed trait DirectoryRequest
  
  case class WatchDirectory(directory: WatchedDirectory) extends DirectoryRequest
  
  case class UnWatchDirectory(id: Long) extends DirectoryRequest

  case class GetWatchedDirectories(startIndex: Long, count: Long) extends DirectoryRequest
    
  case class GetWatchedDirectoryById(watchedDirectoryId: Long) extends DirectoryRequest
  

  case class WatchedDirectories(directories: Seq[WatchedDirectory])
  
  
  case class DirectoryUnwatched(id: Long)

  case class FileAddedToWatchedDirectory(filePath: Path)

}
