package dev.xdark.versionchanger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class CLI {
	private final VersionChanger versionChanger;
	private byte[] buf = new byte[16384];
	private Path tempPath;

	private CLI(VersionChanger versionChanger) {
		this.versionChanger = versionChanger;
	}

	public static void main(String[] args) {
		if (args.length < 3) {
			System.err.println("... majorFrom majorTo [...files]");
			System.exit(1);
		}
		final int versionOffset = 44;
		int majorFrom = Integer.parseInt(args[0]) + versionOffset;
		int majorTo = Integer.parseInt(args[1]) + versionOffset;
		String line = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
		StringBuilder pathBuilder = new StringBuilder();
		List<Path> paths = new ArrayList<>(8);
		PrimitiveIterator.OfInt codePoints = line.codePoints().iterator();
		boolean seenQuote = false;
		final int escapeSentinel = Integer.MAX_VALUE;
		int escapedChar = escapeSentinel;
		boolean consumed = false;
		while (true) {
			if (escapedChar != escapeSentinel) {
				if (escapedChar == '"') {
					pathBuilder.append('"');
				} else {
					pathBuilder.append('\\').appendCodePoint(escapedChar);
				}
				escapedChar = escapeSentinel;
				continue;
			}
			if (codePoints.hasNext()) {
				int ch = codePoints.nextInt();
				if (ch == '\\') {
					escapedChar = codePoints.nextInt();
					consumed = false;
					continue;
				}
				if (ch == '"') {
					if (seenQuote) {
						paths.add(Path.of(pathBuilder.toString()));
						pathBuilder.setLength(0);
						consumed = true;
						seenQuote = false;
					} else {
						seenQuote = true;
						consumed = false;
					}
					continue;
				}
				if (seenQuote) {
					pathBuilder.appendCodePoint(ch);
					continue;
				}
				if (ch == ' ') {
					if (consumed) {
						continue;
					}
					paths.add(Path.of(pathBuilder.toString()));
					pathBuilder.setLength(0);
					consumed = true;
					continue;
				}
				consumed = false;
				pathBuilder.appendCodePoint(ch);
				continue;
			}
			break;
		}
		if (seenQuote) {
			System.err.println("Unclosed path quote");
			System.exit(1);
		}
		if (!pathBuilder.isEmpty()) {
			paths.add(Path.of(pathBuilder.toString()));
		}
		for (Path p : paths) {
			if (!isValid(p)) {
				System.err.println("Path %s is not valid (not a .class, not a directory and not a .zip/.jar file)".formatted(p));
				System.exit(1);
			}
			if (Files.isDirectory(p)) {
				if (Boolean.getBoolean("dev.xdark.versionchanger.allowDirectoryChanges")) continue;
				System.err.println("Seen directory '%s' in the path, it wont be changed. Did you make a mistake?".formatted(p));
				System.err.println("Set -Ddev.xdark.versionchanger.allowDirectoryChanges=true to allow directory changes.");
				System.exit(1);
			}
		}
		CLI cli = new CLI(new VersionChanger() {
			@Override
			protected VersionChange changeVersion(int majorVersion, int minorVersion) {
				if (majorVersion == majorFrom) majorVersion = majorTo;
				return new VersionChange(majorVersion, minorVersion);
			}
		});
		int code = 0;
		doit:
		try {
			for (Path p : paths) {
				try {
					cli.changePath(p);
				} catch (Exception ex) {
					System.err.println("Failed to change class version of %s".formatted(p));
					ex.printStackTrace();
					code = 1;
					break doit;
				}
			}
			System.out.println("OK");
		} finally {
			cli.clean();
		}
		System.exit(code);
	}

	private static boolean isValid(Path path) {
		if (Files.isDirectory(path)) return true;
		if (Files.isRegularFile(path)) {
			String fileName = path.getFileName().toString();
			if (fileName.endsWith(".class")) return true;
			if (fileName.endsWith(".zip") || fileName.endsWith(".jar")) return true;
		}
		return false;
	}

	private void clean() {
		Path tempPath = this.tempPath;
		if (tempPath != null) {
			try {
				Files.deleteIfExists(tempPath);
			} catch (IOException ignored) {
			}
		}
	}

	private Path getTempPath() throws IOException {
		Path tempPath = this.tempPath;
		if (tempPath == null) {
			tempPath = Files.createTempFile("versionchanger", ".bin");
			this.tempPath = tempPath;
		}
		return tempPath;
	}

	private void changePath(Path path) throws IOException {
		String fileName = path.getFileName().toString();
		if (fileName.endsWith(".class")) {
			try (InputStream in = versionChanger.change(new ByteArrayInputStream(Files.readAllBytes(path)))) {
				Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
			}
			return;
		}
		if (Files.isDirectory(path)) {
			Files.walkFileTree(path, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					changePath(path);
					return FileVisitResult.CONTINUE;
				}
			});
			return;
		}
		if (fileName.endsWith(".zip") || fileName.endsWith(".jar")) {
			Path tempPath = getTempPath();
			try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(path));
			     ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempPath))) {
				patchZip(zis, zos);
			}
			Files.copy(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
			return;
		}
		throw new IllegalStateException("::isValid should've caught this");
	}

	private void patchZip(ZipInputStream in, ZipOutputStream out) throws IOException {
		VersionChanger versionChanger = this.versionChanger;
		byte[] buf = this.buf;
		CRC32 crc32 = new CRC32();
		ZipEntry ze;
		while ((ze = in.getNextEntry()) != null) {
			ze = (ZipEntry) ze.clone();
			if (ze.isDirectory()) {
				out.putNextEntry(ze);
				continue;
			}
			InputStream read = in;
			if (ze.getName().endsWith(".class")) {
				read = versionChanger.change(read);
			}
			ze.setCompressedSize(-1L);
			crc32.reset();
			int off = 0;
			while (true) {
				int r = read.read(buf, off, buf.length - off);
				if (r == -1) {
					break;
				}
				if (r == 0) {
					buf = Arrays.copyOf(buf, buf.length + 4096);
					continue;
				}
				crc32.update(buf, off, r);
				off += r;
			}
			ze.setCrc(crc32.getValue());
			out.putNextEntry(ze);
			out.write(buf, 0, off);
			out.closeEntry();
		}
		this.buf = buf;
	}
}
