/*
 * Copyright (C) 2018, Santiago M. Mola <santi@mola.io>
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

package org.eclipse.jgit.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.*;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class IOTest {

	@Rule
	public ExpectedException thrown = ExpectedException.none();

	private File trash;

	@Before
	public void setUp() throws Exception {
		Files.createTempDirectory("tmp_");
		trash = File.createTempFile("tmp_", "");
		trash.delete();
		assertTrue("mkdir " + trash, trash.mkdir());
	}

	@After
	public void tearDown() throws Exception {
		FileUtils.delete(trash.toPath(), FileUtils.RECURSIVE | FileUtils.RETRY);
	}

	@Test
	public void testReadFullyNonExistent() throws Exception {
		thrown.expect(FileNotFoundException.class);
		IO.readFully(new File(trash, "NON-EXISTENT"));
	}

	@Test
	public void testReadFullyEmpty() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());

		final byte[] result = IO.readFully(file);
		assertEquals(0, result.length);
	}

	@Test
	public void testReadFully() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());
		Files.write(file.toPath(), "0123456789".getBytes());

		final byte[] result = IO.readFully(file);
		assertEquals(10, result.length);
	}

	@Test
	public void testReadFullyNNonExistent() throws Exception {
		thrown.expect(FileNotFoundException.class);
		IO.readFully(new File(trash, "NON-EXISTENT"), 100);
	}

	@Test
	public void testReadFullyNEmpty() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());

		final byte[] result = IO.readFully(file, 100);
		assertEquals(0, result.length);
	}

	@Test
	public void testReadFullyN() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());
		Files.write(file.toPath(), "0123456789".getBytes());

		final byte[] result = IO.readFully(file, 100);
		assertEquals(10, result.length);
	}

	@Test
	public void testReadFullyTooBig() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());
		Files.write(file.toPath(), "0123456789".getBytes());

		thrown.expect(IOException.class);
		IO.readFully(file, 5);
	}

	@Test
	public void testReadSomeNonExistent() throws Exception {
		thrown.expect(FileNotFoundException.class);
		IO.readSome(new File(trash, "NON-EXISTENT"), 100);
	}

	@Test
	public void testReadSomeEmpty0() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());

		final byte[] result = IO.readSome(file, 0);
		assertEquals(0, result.length);
	}

	@Test
	public void testReadSomeEmpty100() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());

		final byte[] result = IO.readSome(file, 100);
		assertEquals(0, result.length);
	}

	@Test
	public void testReadSome0() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());
		Files.write(file.toPath(), "0123456789".getBytes());

		final byte[] result = IO.readSome(file, 0);
		assertEquals(0, result.length);
	}

	@Test
	public void testReadSomeFull() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());
		Files.write(file.toPath(), "0123456789".getBytes());

		final byte[] result = IO.readSome(file, 100);
		assertEquals(10, result.length);
	}


	@Test
	public void testReadSomeTruncated() throws Exception {
		final File file = new File(trash, "file");
		assertTrue(file.createNewFile());
		Files.write(file.toPath(), "0123456789".getBytes());

		final byte[] result = IO.readSome(file, 5);
		assertEquals(5, result.length);
	}
}
