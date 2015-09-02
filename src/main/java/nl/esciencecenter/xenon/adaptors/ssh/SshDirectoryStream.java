/*
 * Copyright 2013 Netherlands eScience Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.esciencecenter.xenon.adaptors.ssh;

import java.util.List;

import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.adaptors.generic.DirectoryStreamBase;
import nl.esciencecenter.xenon.files.Path;

import com.jcraft.jsch.ChannelSftp.LsEntry;

public class SshDirectoryStream extends DirectoryStreamBase<LsEntry, Path> {

    public SshDirectoryStream(Path dir, nl.esciencecenter.xenon.files.DirectoryStream.Filter filter, List<LsEntry> listing)
            throws XenonException {
        super(dir, filter, listing);
    }

    @Override
    protected Path getStreamElementFromEntry(LsEntry entry, Path entryPath) {
        return entryPath;
    }

    @Override
    protected String getFileNameFromEntry(LsEntry entry, Path parentPath) {
        return entry.getFilename();
    }
}