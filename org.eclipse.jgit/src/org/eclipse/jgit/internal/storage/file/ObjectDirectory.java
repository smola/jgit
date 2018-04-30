/*
 * Copyright (C) 2009, Google Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.internal.storage.file;

import static org.eclipse.jgit.internal.storage.pack.PackExt.INDEX;
import static org.eclipse.jgit.internal.storage.pack.PackExt.PACK;

import java.io.*;
import java.nio.file.*;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.PackInvalidException;
import org.eclipse.jgit.errors.PackMismatchException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.ObjectToPack;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Traditional file system based {@link org.eclipse.jgit.lib.ObjectDatabase}.
 * <p>
 * This is the classical object database representation for a Git repository,
 * where objects are stored loose by hashing them into directories by their
 * {@link org.eclipse.jgit.lib.ObjectId}, or are stored in compressed containers
 * known as {@link org.eclipse.jgit.internal.storage.file.PackFile}s.
 * <p>
 * Optionally an object database can reference one or more alternates; other
 * ObjectDatabase instances that are searched in addition to the current
 * database.
 * <p>
 * Databases are divided into two halves: a half that is considered to be fast
 * to search (the {@code PackFile}s), and a half that is considered to be slow
 * to search (loose objects). When alternates are present the fast half is fully
 * searched (recursively through all alternates) before the slow half is
 * considered.
 */
public class ObjectDirectory extends FileObjectDatabase {
	private final static Logger LOG = LoggerFactory
			.getLogger(ObjectDirectory.class);

	private static final PackList NO_PACKS = new PackList(
			FileSnapshot.DIRTY, new PackFile[0]);

	/** Maximum number of candidates offered as resolutions of abbreviation. */
	private static final int RESOLVE_ABBREV_LIMIT = 256;

	private final AlternateHandle handle = new AlternateHandle(this);

	private final Config config;

	private final Path objects;

	private final Path infoDirectory;

	private final Path packDirectory;

	private final Path preservedDirectory;

	private final Path alternatesFile;

	private final AtomicReference<PackList> packList;

	private final FS fs;

	private final AtomicReference<AlternateHandle[]> alternates;

	private final UnpackedObjectCache unpackedObjectCache;

	private final Path shallowFile;

	private FileSnapshot shallowFileSnapshot = FileSnapshot.DIRTY;

	private Set<ObjectId> shallowCommitsIds;

	/**
	 * Initialize a reference to an on-disk object directory.
	 *
	 * @param cfg
	 *            configuration this directory consults for write settings.
	 * @param dir
	 *            the location of the <code>objects</code> directory.
	 * @param alternatePaths
	 *            a list of alternate object directories
	 * @param fs
	 *            the file system abstraction which will be necessary to perform
	 *            certain file system operations.
	 * @param shallowFile
	 *            file which contains IDs of shallow commits, null if shallow
	 *            commits handling should be turned off
	 * @throws java.io.IOException
	 *             an alternate object cannot be opened.
	 */
	public ObjectDirectory(final Config cfg, final Path dir,
			Path[] alternatePaths, FS fs, Path shallowFile) throws IOException {
		config = cfg;
		objects = dir;
		infoDirectory = objects.resolve("info"); //$NON-NLS-1$
		packDirectory = objects.resolve("path"); //$NON-NLS-1$
		preservedDirectory = packDirectory.resolve("preserved"); //$NON-NLS-1$
		alternatesFile = infoDirectory.resolve("alternates"); //$NON-NLS-1$
		packList = new AtomicReference<>(NO_PACKS);
		unpackedObjectCache = new UnpackedObjectCache();
		this.fs = fs;
		this.shallowFile = shallowFile;

		alternates = new AtomicReference<>();
		if (alternatePaths != null) {
			AlternateHandle[] alt;

			alt = new AlternateHandle[alternatePaths.length];
			for (int i = 0; i < alternatePaths.length; i++)
				alt[i] = openAlternate(alternatePaths[i]);
			alternates.set(alt);
		}
	}

	/** {@inheritDoc} */
	@Override
	public final Path getDirectory() {
		return objects;
	}

	/**
	 * <p>Getter for the field <code>packDirectory</code>.</p>
	 *
	 * @return the location of the <code>pack</code> directory.
	 * @since 4.10
	 */
	public final Path getPackDirectory() {
		return packDirectory;
	}

	/**
	 * <p>Getter for the field <code>preservedDirectory</code>.</p>
	 *
	 * @return the location of the <code>preserved</code> directory.
	 */
	public final Path getPreservedDirectory() {
		return preservedDirectory;
	}

	/** {@inheritDoc} */
	@Override
	public boolean exists() {
		return Files.exists(objects);
	}

	/** {@inheritDoc} */
	@Override
	public void create() throws IOException {
		FileUtils.mkdirs(objects.toFile());
		FileUtils.mkdir(infoDirectory.toFile());
		FileUtils.mkdir(packDirectory.toFile());
	}

	/** {@inheritDoc} */
	@Override
	public ObjectDirectoryInserter newInserter() {
		return new ObjectDirectoryInserter(this, config);
	}

	/**
	 * Create a new inserter that inserts all objects as pack files, not loose
	 * objects.
	 *
	 * @return new inserter.
	 */
	public PackInserter newPackInserter() {
		return new PackInserter(this);
	}

	/** {@inheritDoc} */
	@Override
	public void close() {
		unpackedObjectCache.clear();

		final PackList packs = packList.get();
		if (packs != NO_PACKS && packList.compareAndSet(packs, NO_PACKS)) {
			for (PackFile p : packs.packs)
				p.close();
		}

		// Fully close all loaded alternates and clear the alternate list.
		AlternateHandle[] alt = alternates.get();
		if (alt != null && alternates.compareAndSet(alt, null)) {
			for(final AlternateHandle od : alt)
				od.close();
		}
	}

	/** {@inheritDoc} */
	@Override
	public Collection<PackFile> getPacks() {
		PackList list = packList.get();
		if (list == NO_PACKS)
			list = scanPacks(list);
		PackFile[] packs = list.packs;
		return Collections.unmodifiableCollection(Arrays.asList(packs));
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Add a single existing pack to the list of available pack files.
	 */
	@Override
	public PackFile openPack(final Path pack)
			throws IOException {
		final String p = pack.getFileName().toString();
		if (p.length() != 50 || !p.startsWith("pack-") || !p.endsWith(".pack")) //$NON-NLS-1$ //$NON-NLS-2$
			throw new IOException(MessageFormat.format(JGitText.get().notAValidPack, pack));

		// The pack and index are assumed to exist. The existence of other
		// extensions needs to be explicitly checked.
		//
		int extensions = PACK.getBit() | INDEX.getBit();
		final String base = p.substring(0, p.length() - 4);
		for (PackExt ext : PackExt.values()) {
			if ((extensions & ext.getBit()) == 0) {
				final String name = base + ext.getExtension();
				if (Files.exists(pack.getParent().resolve(name)))
					extensions |= ext.getBit();
			}
		}

		PackFile res = new PackFile(pack.toFile(), extensions);
		insertPack(res);
		return res;
	}

	/** {@inheritDoc} */
	@Override
	public String toString() {
		return "ObjectDirectory[" + getDirectory() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** {@inheritDoc} */
	@Override
	public boolean has(AnyObjectId objectId) {
		return unpackedObjectCache.isUnpacked(objectId)
				|| hasPackedInSelfOrAlternate(objectId, null)
				|| hasLooseInSelfOrAlternate(objectId, null);
	}

	private boolean hasPackedInSelfOrAlternate(AnyObjectId objectId,
			Set<AlternateHandle.Id> skips) {
		if (hasPackedObject(objectId)) {
			return true;
		}
		skips = addMe(skips);
		for (AlternateHandle alt : myAlternates()) {
			if (!skips.contains(alt.getId())) {
				if (alt.db.hasPackedInSelfOrAlternate(objectId, skips)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean hasLooseInSelfOrAlternate(AnyObjectId objectId,
			Set<AlternateHandle.Id> skips) {
		if (Files.exists(fileFor(objectId))) {
			return true;
		}
		skips = addMe(skips);
		for (AlternateHandle alt : myAlternates()) {
			if (!skips.contains(alt.getId())) {
				if (alt.db.hasLooseInSelfOrAlternate(objectId, skips)) {
					return true;
				}
			}
		}
		return false;
	}

	boolean hasPackedObject(AnyObjectId objectId) {
		PackList pList;
		do {
			pList = packList.get();
			for (PackFile p : pList.packs) {
				try {
					if (p.hasObject(objectId))
						return true;
				} catch (IOException e) {
					// The hasObject call should have only touched the index,
					// so any failure here indicates the index is unreadable
					// by this process, and the pack is likewise not readable.
					removePack(p);
				}
			}
		} while (searchPacksAgain(pList));
		return false;
	}

	@Override
	void resolve(Set<ObjectId> matches, AbbreviatedObjectId id)
			throws IOException {
		resolve(matches, id, null);
	}

	private void resolve(Set<ObjectId> matches, AbbreviatedObjectId id,
			Set<AlternateHandle.Id> skips)
			throws IOException {
		// Go through the packs once. If we didn't find any resolutions
		// scan for new packs and check once more.
		int oldSize = matches.size();
		PackList pList;
		do {
			pList = packList.get();
			for (PackFile p : pList.packs) {
				try {
					p.resolve(matches, id, RESOLVE_ABBREV_LIMIT);
					p.resetTransientErrorCount();
				} catch (IOException e) {
					handlePackError(e, p);
				}
				if (matches.size() > RESOLVE_ABBREV_LIMIT)
					return;
			}
		} while (matches.size() == oldSize && searchPacksAgain(pList));

		String fanOut = id.name().substring(0, 2);
		List<Path> entries = null;
		try (Stream<Path> s = Files.list(getDirectory().resolve(fanOut))) {
			entries = s.collect(Collectors.toList());
		} catch (IOException ex) {

		}
		if (entries != null) {
			for (Path e : entries) {
				String fileName = e.getFileName().toString();
				if (fileName.length() != Constants.OBJECT_ID_STRING_LENGTH - 2)
					continue;
				try {
					ObjectId entId = ObjectId.fromString(fanOut + e.getFileName());
					if (id.prefixCompare(entId) == 0)
						matches.add(entId);
				} catch (IllegalArgumentException notId) {
					continue;
				}
				if (matches.size() > RESOLVE_ABBREV_LIMIT)
					return;
			}
		}

		skips = addMe(skips);
		for (AlternateHandle alt : myAlternates()) {
			if (!skips.contains(alt.getId())) {
				alt.db.resolve(matches, id, skips);
				if (matches.size() > RESOLVE_ABBREV_LIMIT) {
					return;
				}
			}
		}
	}

	@Override
	ObjectLoader openObject(WindowCursor curs, AnyObjectId objectId)
			throws IOException {
		if (unpackedObjectCache.isUnpacked(objectId)) {
			ObjectLoader ldr = openLooseObject(curs, objectId);
			if (ldr != null) {
				return ldr;
			}
		}
		ObjectLoader ldr = openPackedFromSelfOrAlternate(curs, objectId, null);
		if (ldr != null) {
			return ldr;
		}
		return openLooseFromSelfOrAlternate(curs, objectId, null);
	}

	private ObjectLoader openPackedFromSelfOrAlternate(WindowCursor curs,
			AnyObjectId objectId, Set<AlternateHandle.Id> skips) {
		ObjectLoader ldr = openPackedObject(curs, objectId);
		if (ldr != null) {
			return ldr;
		}
		skips = addMe(skips);
		for (AlternateHandle alt : myAlternates()) {
			if (!skips.contains(alt.getId())) {
				ldr = alt.db.openPackedFromSelfOrAlternate(curs, objectId, skips);
				if (ldr != null) {
					return ldr;
				}
			}
		}
		return null;
	}

	private ObjectLoader openLooseFromSelfOrAlternate(WindowCursor curs,
			AnyObjectId objectId, Set<AlternateHandle.Id> skips)
					throws IOException {
		ObjectLoader ldr = openLooseObject(curs, objectId);
		if (ldr != null) {
			return ldr;
		}
		skips = addMe(skips);
		for (AlternateHandle alt : myAlternates()) {
			if (!skips.contains(alt.getId())) {
				ldr = alt.db.openLooseFromSelfOrAlternate(curs, objectId, skips);
				if (ldr != null) {
					return ldr;
				}
			}
		}
		return null;
	}

	ObjectLoader openPackedObject(WindowCursor curs, AnyObjectId objectId) {
		PackList pList;
		do {
			SEARCH: for (;;) {
				pList = packList.get();
				for (PackFile p : pList.packs) {
					try {
						ObjectLoader ldr = p.get(curs, objectId);
						p.resetTransientErrorCount();
						if (ldr != null)
							return ldr;
					} catch (PackMismatchException e) {
						// Pack was modified; refresh the entire pack list.
						if (searchPacksAgain(pList))
							continue SEARCH;
					} catch (IOException e) {
						handlePackError(e, p);
					}
				}
				break SEARCH;
			}
		} while (searchPacksAgain(pList));
		return null;
	}

	@Override
	ObjectLoader openLooseObject(WindowCursor curs, AnyObjectId id)
			throws IOException {
		Path path = fileFor(id);
		try (InputStream in = Files.newInputStream(path)) {
			unpackedObjectCache.add(id);
			return UnpackedObject.open(in, path.toFile(), id, curs);
		} catch (NoSuchFileException noFile) {
			if (Files.exists(path)) {
				throw noFile;
			}
			unpackedObjectCache.remove(id);
			return null;
		}
	}

	@Override
	long getObjectSize(WindowCursor curs, AnyObjectId id)
			throws IOException {
		if (unpackedObjectCache.isUnpacked(id)) {
			long len = getLooseObjectSize(curs, id);
			if (0 <= len) {
				return len;
			}
		}
		long len = getPackedSizeFromSelfOrAlternate(curs, id, null);
		if (0 <= len) {
			return len;
		}
		return getLooseSizeFromSelfOrAlternate(curs, id, null);
	}

	private long getPackedSizeFromSelfOrAlternate(WindowCursor curs,
			AnyObjectId id, Set<AlternateHandle.Id> skips) {
		long len = getPackedObjectSize(curs, id);
		if (0 <= len) {
			return len;
		}
		skips = addMe(skips);
		for (AlternateHandle alt : myAlternates()) {
			if (!skips.contains(alt.getId())) {
				len = alt.db.getPackedSizeFromSelfOrAlternate(curs, id, skips);
				if (0 <= len) {
					return len;
				}
			}
		}
		return -1;
	}

	private long getLooseSizeFromSelfOrAlternate(WindowCursor curs,
			AnyObjectId id, Set<AlternateHandle.Id> skips) throws IOException {
		long len = getLooseObjectSize(curs, id);
		if (0 <= len) {
			return len;
		}
		skips = addMe(skips);
		for (AlternateHandle alt : myAlternates()) {
			if (!skips.contains(alt.getId())) {
				len = alt.db.getLooseSizeFromSelfOrAlternate(curs, id, skips);
				if (0 <= len) {
					return len;
				}
			}
		}
		return -1;
	}

	private long getPackedObjectSize(WindowCursor curs, AnyObjectId id) {
		PackList pList;
		do {
			SEARCH: for (;;) {
				pList = packList.get();
				for (PackFile p : pList.packs) {
					try {
						long len = p.getObjectSize(curs, id);
						p.resetTransientErrorCount();
						if (0 <= len)
							return len;
					} catch (PackMismatchException e) {
						// Pack was modified; refresh the entire pack list.
						if (searchPacksAgain(pList))
							continue SEARCH;
					} catch (IOException e) {
						handlePackError(e, p);
					}
				}
				break SEARCH;
			}
		} while (searchPacksAgain(pList));
		return -1;
	}

	private long getLooseObjectSize(WindowCursor curs, AnyObjectId id)
			throws IOException {
		Path f = fileFor(id);
		try (InputStream in = Files.newInputStream(f)) {
			unpackedObjectCache.add(id);
			return UnpackedObject.getSize(in, id, curs);
		} catch (FileNotFoundException noFile) {
			if (Files.exists(f)) {
				throw noFile;
			}
			unpackedObjectCache.remove(id);
			return -1;
		}
	}

	@Override
	void selectObjectRepresentation(PackWriter packer, ObjectToPack otp,
																	WindowCursor curs) throws IOException {
		selectObjectRepresentation(packer, otp, curs, null);
	}

	private void selectObjectRepresentation(PackWriter packer, ObjectToPack otp,
			WindowCursor curs, Set<AlternateHandle.Id> skips) throws IOException {
		PackList pList = packList.get();
		SEARCH: for (;;) {
			for (final PackFile p : pList.packs) {
				try {
					LocalObjectRepresentation rep = p.representation(curs, otp);
					p.resetTransientErrorCount();
					if (rep != null)
						packer.select(otp, rep);
				} catch (PackMismatchException e) {
					// Pack was modified; refresh the entire pack list.
					//
					pList = scanPacks(pList);
					continue SEARCH;
				} catch (IOException e) {
					handlePackError(e, p);
				}
			}
			break SEARCH;
		}

		skips = addMe(skips);
		for (AlternateHandle h : myAlternates()) {
			if (!skips.contains(h.getId())) {
				h.db.selectObjectRepresentation(packer, otp, curs, skips);
			}
		}
	}

	private void handlePackError(IOException e, PackFile p) {
		String warnTmpl = null;
		int transientErrorCount = 0;
		String errTmpl = JGitText.get().exceptionWhileReadingPack;
		if ((e instanceof CorruptObjectException)
				|| (e instanceof PackInvalidException)) {
			warnTmpl = JGitText.get().corruptPack;
			// Assume the pack is corrupted, and remove it from the list.
			removePack(p);
		} else if (e instanceof FileNotFoundException) {
			if (p.getPackFile().exists()) {
				errTmpl = JGitText.get().packInaccessible;
				transientErrorCount = p.incrementTransientErrorCount();
			} else {
				warnTmpl = JGitText.get().packWasDeleted;
				removePack(p);
			}
		} else if (FileUtils.isStaleFileHandleInCausalChain(e)) {
			warnTmpl = JGitText.get().packHandleIsStale;
			removePack(p);
		} else {
			transientErrorCount = p.incrementTransientErrorCount();
		}
		if (warnTmpl != null) {
			if (LOG.isDebugEnabled()) {
				LOG.debug(MessageFormat.format(warnTmpl,
						p.getPackFile().getAbsolutePath()), e);
			} else {
				LOG.warn(MessageFormat.format(warnTmpl,
						p.getPackFile().getAbsolutePath()));
			}
		} else {
			if (doLogExponentialBackoff(transientErrorCount)) {
				// Don't remove the pack from the list, as the error may be
				// transient.
				LOG.error(MessageFormat.format(errTmpl,
						p.getPackFile().getAbsolutePath()),
						Integer.valueOf(transientErrorCount), e);
			}
		}
	}

	/**
	 * @param n
	 *            count of consecutive failures
	 * @return @{code true} if i is a power of 2
	 */
	private boolean doLogExponentialBackoff(int n) {
		return (n & (n - 1)) == 0;
	}

	@Override
	InsertLooseObjectResult insertUnpackedObject(Path tmp, ObjectId id,
			boolean createDuplicate) throws IOException {
		// If the object is already in the repository, remove temporary file.
		//
		if (unpackedObjectCache.isUnpacked(id)) {
			FileUtils.delete(tmp, FileUtils.RETRY);
			return InsertLooseObjectResult.EXISTS_LOOSE;
		}
		if (!createDuplicate && has(id)) {
			FileUtils.delete(tmp, FileUtils.RETRY);
			return InsertLooseObjectResult.EXISTS_PACKED;
		}

		final Path dst = fileFor(id);
		if (Files.exists(dst)) {
			// We want to be extra careful and avoid replacing an object
			// that already exists. We can't be sure renameTo() would
			// fail on all platforms if dst exists, so we check first.
			//
			FileUtils.delete(tmp, FileUtils.RETRY);
			return InsertLooseObjectResult.EXISTS_LOOSE;
		}
		try {
			Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE);
			fs.setReadOnly(dst);
			unpackedObjectCache.add(id);
			return InsertLooseObjectResult.INSERTED;
		} catch (AtomicMoveNotSupportedException e) {
			LOG.error(e.getMessage(), e);
		} catch (IOException e) {
			// ignore
		}

		// Maybe the directory doesn't exist yet as the object
		// directories are always lazily created. Note that we
		// try the rename first as the directory likely does exist.
		//
		FileUtils.mkdir(dst.getParent().toFile(), true);
		try {
			Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE);
			fs.setReadOnly(dst);
			unpackedObjectCache.add(id);
			return InsertLooseObjectResult.INSERTED;
		} catch (AtomicMoveNotSupportedException e) {
			LOG.error(e.getMessage(), e);
		} catch (IOException e) {
			LOG.debug(e.getMessage(), e);
		}

		if (!createDuplicate && has(id)) {
			FileUtils.delete(tmp, FileUtils.RETRY);
			return InsertLooseObjectResult.EXISTS_PACKED;
		}

		// The object failed to be renamed into its proper
		// location and it doesn't exist in the repository
		// either. We really don't know what went wrong, so
		// fail.
		//
		FileUtils.delete(tmp, FileUtils.RETRY);
		return InsertLooseObjectResult.FAILURE;
	}

	private boolean searchPacksAgain(PackList old) {
		// Whether to trust the pack folder's modification time. If set
		// to false we will always scan the .git/objects/pack folder to
		// check for new pack files. If set to true (default) we use the
		// lastmodified attribute of the folder and assume that no new
		// pack files can be in this folder if his modification time has
		// not changed.
		boolean trustFolderStat = config.getBoolean(
				ConfigConstants.CONFIG_CORE_SECTION,
				ConfigConstants.CONFIG_KEY_TRUSTFOLDERSTAT, true);

		return ((!trustFolderStat) || old.snapshot.isModified(packDirectory))
				&& old != scanPacks(old);
	}

	@Override
	Config getConfig() {
		return config;
	}

	@Override
	FS getFS() {
		return fs;
	}

	@Override
	Set<ObjectId> getShallowCommits() throws IOException {
		if (shallowFile == null || !Files.isRegularFile(shallowFile))
			return Collections.emptySet();

		if (shallowFileSnapshot == null
				|| shallowFileSnapshot.isModified(shallowFile)) {
			shallowCommitsIds = new HashSet<>();

			try (BufferedReader reader = Files.newBufferedReader(shallowFile)) {
				String line;
				while ((line = reader.readLine()) != null) {
					try {
						shallowCommitsIds.add(ObjectId.fromString(line));
					} catch (IllegalArgumentException ex) {
						throw new IOException(MessageFormat
								.format(JGitText.get().badShallowLine, line));
					}
				}
			}

			shallowFileSnapshot = FileSnapshot.save(shallowFile);
		}

		return shallowCommitsIds;
	}

	private void insertPack(final PackFile pf) {
		PackList o, n;
		do {
			o = packList.get();

			// If the pack in question is already present in the list
			// (picked up by a concurrent thread that did a scan?) we
			// do not want to insert it a second time.
			//
			final PackFile[] oldList = o.packs;
			final String name = pf.getPackFile().getName();
			for (PackFile p : oldList) {
				if (name.equals(p.getPackFile().getName()))
					return;
			}

			final PackFile[] newList = new PackFile[1 + oldList.length];
			newList[0] = pf;
			System.arraycopy(oldList, 0, newList, 1, oldList.length);
			n = new PackList(o.snapshot, newList);
		} while (!packList.compareAndSet(o, n));
	}

	private void removePack(final PackFile deadPack) {
		PackList o, n;
		do {
			o = packList.get();

			final PackFile[] oldList = o.packs;
			final int j = indexOf(oldList, deadPack);
			if (j < 0)
				break;

			final PackFile[] newList = new PackFile[oldList.length - 1];
			System.arraycopy(oldList, 0, newList, 0, j);
			System.arraycopy(oldList, j + 1, newList, j, newList.length - j);
			n = new PackList(o.snapshot, newList);
		} while (!packList.compareAndSet(o, n));
		deadPack.close();
	}

	private static int indexOf(final PackFile[] list, final PackFile pack) {
		for (int i = 0; i < list.length; i++) {
			if (list[i] == pack)
				return i;
		}
		return -1;
	}

	private PackList scanPacks(final PackList original) {
		synchronized (packList) {
			PackList o, n;
			do {
				o = packList.get();
				if (o != original) {
					// Another thread did the scan for us, while we
					// were blocked on the monitor above.
					//
					return o;
				}
				n = scanPacksImpl(o);
				if (n == o)
					return n;
			} while (!packList.compareAndSet(o, n));
			return n;
		}
	}

	private PackList scanPacksImpl(final PackList old) {
		final Map<String, PackFile> forReuse = reuseMap(old);
		final FileSnapshot snapshot = FileSnapshot.save(packDirectory);
		final Set<String> names = listPackDirectory();
		final List<PackFile> list = new ArrayList<>(names.size() >> 2);
		boolean foundNew = false;
		for (final String indexName : names) {
			// Must match "pack-[0-9a-f]{40}.idx" to be an index.
			//
			if (indexName.length() != 49 || !indexName.endsWith(".idx")) //$NON-NLS-1$
				continue;

			final String base = indexName.substring(0, indexName.length() - 3);
			int extensions = 0;
			for (PackExt ext : PackExt.values()) {
				if (names.contains(base + ext.getExtension()))
					extensions |= ext.getBit();
			}

			if ((extensions & PACK.getBit()) == 0) {
				// Sometimes C Git's HTTP fetch transport leaves a
				// .idx file behind and does not download the .pack.
				// We have to skip over such useless indexes.
				//
				continue;
			}

			final String packName = base + PACK.getExtension();
			final PackFile oldPack = forReuse.remove(packName);
			if (oldPack != null) {
				list.add(oldPack);
				continue;
			}

			final Path packFile = packDirectory.resolve(packName);
			list.add(new PackFile(packFile.toFile(), extensions));
			foundNew = true;
		}

		// If we did not discover any new files, the modification time was not
		// changed, and we did not remove any files, then the set of files is
		// the same as the set we were given. Instead of building a new object
		// return the same collection.
		//
		if (!foundNew && forReuse.isEmpty() && snapshot.equals(old.snapshot)) {
			old.snapshot.setClean(snapshot);
			return old;
		}

		for (final PackFile p : forReuse.values()) {
			p.close();
		}

		if (list.isEmpty())
			return new PackList(snapshot, NO_PACKS.packs);

		final PackFile[] r = list.toArray(new PackFile[list.size()]);
		Arrays.sort(r, PackFile.SORT);
		return new PackList(snapshot, r);
	}

	private static Map<String, PackFile> reuseMap(final PackList old) {
		final Map<String, PackFile> forReuse = new HashMap<>();
		for (final PackFile p : old.packs) {
			if (p.invalid()) {
				// The pack instance is corrupted, and cannot be safely used
				// again. Do not include it in our reuse map.
				//
				p.close();
				continue;
			}

			final PackFile prior = forReuse.put(p.getPackFile().getName(), p);
			if (prior != null) {
				// This should never occur. It should be impossible for us
				// to have two pack files with the same name, as all of them
				// came out of the same directory. If it does, we promised to
				// close any PackFiles we did not reuse, so close the second,
				// readers are likely to be actively using the first.
				//
				forReuse.put(prior.getPackFile().getName(), prior);
				p.close();
			}
		}
		return forReuse;
	}

	private Set<String> listPackDirectory() {
		try (Stream<Path> ls = Files.list(packDirectory)) {
			return ls
					.map(p -> p.getFileName().toString())
					.filter(s -> s.startsWith("pack-")) //$NON-NLS-1$
					.collect(Collectors.toSet());
		} catch (IOException ex) {
			return Collections.emptySet();
		}
	}

	void closeAllPackHandles(File packFile) {
		// if the packfile already exists (because we are rewriting a
		// packfile for the same set of objects maybe with different
		// PackConfig) then make sure we get rid of all handles on the file.
		// Windows will not allow for rename otherwise.
		if (packFile.exists()) {
			for (PackFile p : getPacks()) {
				if (packFile.getPath().equals(p.getPackFile().getPath())) {
					p.close();
					break;
				}
			}
		}
	}

	AlternateHandle[] myAlternates() {
		AlternateHandle[] alt = alternates.get();
		if (alt == null) {
			synchronized (alternates) {
				alt = alternates.get();
				if (alt == null) {
					try {
						alt = loadAlternates();
					} catch (IOException e) {
						alt = new AlternateHandle[0];
					}
					alternates.set(alt);
				}
			}
		}
		return alt;
	}

	Set<AlternateHandle.Id> addMe(Set<AlternateHandle.Id> skips) {
		if (skips == null) {
			skips = new HashSet<>();
		}
		skips.add(handle.getId());
		return skips;
	}

	private AlternateHandle[] loadAlternates() throws IOException {
		final List<AlternateHandle> l = new ArrayList<>(4);
		try (BufferedReader br = Files.newBufferedReader(alternatesFile)) {
			String line;
			while ((line = br.readLine()) != null) {
				l.add(openAlternate(line));
			}
		}
		return l.toArray(new AlternateHandle[l.size()]);
	}

	private AlternateHandle openAlternate(final String location)
		throws IOException {
		return openAlternate(objects.resolve(location));
	}


	private AlternateHandle openAlternate(Path objdir) throws IOException {
		final Path parent = objdir.getParent();
		if (FileKey.isGitRepository(parent, fs)) {
			FileKey key = FileKey.exact(parent, fs);
			FileRepository db = (FileRepository) RepositoryCache.open(key);
			return new AlternateRepository(db);
		}

		ObjectDirectory db = new ObjectDirectory(config, objdir, null, fs, null);
		return new AlternateHandle(db);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Compute the location of a loose object file.
	 */
	@Override
	public Path fileFor(AnyObjectId objectId) {
		String n = objectId.name();
		String d = n.substring(0, 2);
		String f = n.substring(2);
		return getDirectory().resolve(d).resolve(f);
	}

	private static final class PackList {
		/** State just before reading the pack directory. */
		final FileSnapshot snapshot;

		/** All known packs, sorted by {@link PackFile#SORT}. */
		final PackFile[] packs;

		PackList(final FileSnapshot monitor, final PackFile[] packs) {
			this.snapshot = monitor;
			this.packs = packs;
		}
	}

	static class AlternateHandle {
		static class Id {
			String alternateId;

			public Id(File object) {
				try {
					this.alternateId = object.getCanonicalPath();
				} catch (Exception e) {
					alternateId = null;
				}
			}

			@Override
			public boolean equals(Object o) {
				if (o == this) {
					return true;
				}
				if (o == null || !(o instanceof Id)) {
					return false;
				}
				Id aId = (Id) o;
				return Objects.equals(alternateId, aId.alternateId);
			}

			@Override
			public int hashCode() {
				if (alternateId == null) {
					return 1;
				}
				return alternateId.hashCode();
			}
		}

		final ObjectDirectory db;

		AlternateHandle(ObjectDirectory db) {
			this.db = db;
		}

		void close() {
			db.close();
		}

		public Id getId(){
			return db.getAlternateId();
		}
	}

	static class AlternateRepository extends AlternateHandle {
		final FileRepository repository;

		AlternateRepository(FileRepository r) {
			super(r.getObjectDatabase());
			repository = r;
		}

		@Override
		void close() {
			repository.close();
		}
	}

	/** {@inheritDoc} */
	@Override
	public ObjectDatabase newCachedDatabase() {
		return newCachedFileObjectDatabase();
	}

	CachedObjectDirectory newCachedFileObjectDatabase() {
		return new CachedObjectDirectory(this);
	}

	AlternateHandle.Id getAlternateId() {
		return new AlternateHandle.Id(objects.toFile());
	}
}
