/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.ContentProvider;
import android.content.ContentProvider.PipeDataWriter;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import de.blinkt.openvpn.core.LogItem;
import de.blinkt.openvpn.core.VpnStatus;

/**
 * A very simple content provider that can serve arbitrary asset files from
 * our .apk.
 */
public class FileProvider extends ContentProvider
implements PipeDataWriter<InputStream> {
	private static final String LOG_EXPORT_NAME = "log.txt";

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
			String sortOrder) {
		try {
			String path = getNormalizedPath(uri);
			File dumpfile = LOG_EXPORT_NAME.equals(path) ? null : getFileFromURI(uri);
			MatrixCursor c = new MatrixCursor(projection);

			Object[] row = new Object[projection.length];
			int i=0;
			for (String r:projection) {
				if(r.equals(OpenableColumns.SIZE))
					row[i] = dumpfile == null ? buildLogExport().length : dumpfile.length();
				if(r.equals(OpenableColumns.DISPLAY_NAME))
					row[i] = dumpfile == null ? LOG_EXPORT_NAME : dumpfile.getName();
				i++;
			}
			c.addRow(row);
			return c;
		} catch (FileNotFoundException e) {
            VpnStatus.logException(e);
            return null;
		}


	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		// Don't support inserts.
		return null;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		// Don't support deletes.
		return 0;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		// Don't support updates.
		return 0;
	}

	@Override
	public String getType(Uri uri) {
		if (LOG_EXPORT_NAME.equals(getNormalizedPath(uri)))
			return "text/plain";
		return "application/octet-stream";
	}

	@Override
	public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
		ParcelFileDescriptor fd = openFile(uri, mode);
		long length = LOG_EXPORT_NAME.equals(getNormalizedPath(uri))
				? AssetFileDescriptor.UNKNOWN_LENGTH
				: getFileFromURI(uri).length();
		return new AssetFileDescriptor(fd, 0, length);
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		String path = getNormalizedPath(uri);
		File dumpfile = LOG_EXPORT_NAME.equals(path) ? null : getFileFromURI(uri);

		try {

			byte[] logBytes = dumpfile == null ? buildLogExport() : null;
			InputStream is = dumpfile == null
					? new ByteArrayInputStream(logBytes)
					: new FileInputStream(dumpfile);
			// Start a new thread that pipes the stream data back to the caller.
			return openPipeHelper(uri, null, null, is, this);
		} catch (IOException e) {
            throw new FileNotFoundException("Unable to open " + uri);
		}
	}

	private byte[] buildLogExport() {
		VpnStatus.flushLog();
		SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
		StringBuilder log = new StringBuilder();
		for (LogItem item : VpnStatus.getlogbuffer()) {
			log.append(timeFormat.format(new Date(item.getLogtime())))
					.append(' ')
					.append(item.getLogLevel())
					.append(' ')
					.append(item.getString(getContext()))
					.append('\n');
		}
		return log.toString().getBytes(StandardCharsets.UTF_8);
	}

	private File getFileFromURI(Uri uri) throws FileNotFoundException {
		// Try to open an asset with the given name.
		String path = getNormalizedPath(uri);

		// I think this already random enough, no need for magic secure cookies
		// 1f9563a4-a1f5-2165-255f2219-111823ef.dmp
		if (!path.matches("^[0-9a-z-.]*(dmp|dmp.log)$"))
			throw new FileNotFoundException("url not in expect format " + uri);
		File cachedir = getContext().getCacheDir();
        return new File(cachedir,path);
	}

	private String getNormalizedPath(Uri uri) {
		String path = uri.getPath();
		if(path.startsWith("/"))
			path = path.replaceFirst("/", "");
		return path;
	}

	@Override
	public void writeDataToPipe(ParcelFileDescriptor output, Uri uri, String mimeType,
			Bundle opts, InputStream args) {
		// Transfer data from the asset to the pipe the client is reading.
		byte[] buffer = new byte[8192];
		int n;
		FileOutputStream fout = new FileOutputStream(output.getFileDescriptor());
		try {
			while ((n=args.read(buffer)) >= 0) {
				fout.write(buffer, 0, n);
			}
		} catch (IOException e) {
			Log.i("OpenVPNFileProvider", "Failed transferring", e);
		} finally {
			try {
				args.close();
			} catch (IOException e) {
			}
			try {
				fout.close();
			} catch (IOException e) {
			}
		}
	}
}
