package dev.xdark.versionchanger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

public abstract class VersionChanger {
	private static final VarHandle VH_INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle VH_SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
	static final int HEADER = 0xcafebabe;

	public InputStream change(InputStream in) throws IOException {
		DataInputStream dis = new DataInputStream(in);
		if (HEADER != dis.readInt()) {
			throw new IOException("Stream does not start with %s".formatted(Integer.toHexString(HEADER)));
		}
		int minorVersion = dis.readUnsignedShort();
		int majorVersion = dis.readUnsignedShort();
		VersionChange versionChange = changeVersion(majorVersion, minorVersion);
		Pushback pushback = new Pushback(in);
		byte[] buf = new byte[4 + 2 + 2];
		VH_INT.set(buf, 0, HEADER);
		VH_SHORT.set(buf, 4, (short) versionChange.minorVersion());
		VH_SHORT.set(buf, 6, (short) versionChange.majorVersion());
		pushback.set(buf);
		return pushback;
	}

	protected abstract VersionChange changeVersion(int majorVersion, int minorVersion);

	private static final class Pushback extends PushbackInputStream {

		public Pushback(InputStream in) {
			super(in, 1);
		}

		void set(byte[] buf) {
			this.buf = buf;
			pos = 0;
		}
	}
}
