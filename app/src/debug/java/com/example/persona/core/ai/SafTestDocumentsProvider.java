package com.example.persona.core.ai;

import android.os.Binder;
import android.os.Process;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Enabled only for the isolated validation package; release builds exclude this source.
public final class SafTestDocumentsProvider extends DocumentsProvider {
    private static final class Entry {
        final String id;
        final String parent;
        final String name;
        final ParcelFileDescriptor descriptor;
        final Long size;
        final boolean slow;
        final boolean fail;

        @SuppressWarnings("deprecation")
        Entry(String id, Bundle data) throws IOException {
            this.id = id;
            parent = data.getString("parent");
            name = data.getString("name");
            ParcelFileDescriptor source = data.getParcelable("descriptor");
            descriptor = source == null ? null : source.dup();
            size = data.containsKey("size") ? data.getLong("size") : null;
            slow = data.getBoolean("slow");
            fail = data.getBoolean("fail");
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger opens = new AtomicInteger();
    private final AtomicLong streamedBytes = new AtomicLong();

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle inherited = super.call(method, arg, extras);
        if (inherited != null || !method.startsWith("persona.")) return inherited;
        if (!"com.example.persona.validation".equals(getContext().getPackageName()) ||
                Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Requires the isolated QA test APK");
        }
        switch (method) {
            case "persona.reset":
                for (Entry entry : entries.values()) {
                    if (entry.descriptor != null) {
                        try { entry.descriptor.close(); } catch (IOException ignored) { }
                    }
                }
                entries.clear();
                opens.set(0);
                streamedBytes.set(0);
                return new Bundle();
            case "persona.register":
                try {
                    entries.put(arg, new Entry(arg, extras));
                } catch (IOException error) {
                    throw new IllegalArgumentException("Cannot duplicate fixture descriptor", error);
                }
                return new Bundle();
            case "persona.stats":
                Bundle stats = new Bundle();
                stats.putInt("opens", opens.get());
                stats.putLong("streamedBytes", streamedBytes.get());
                return stats;
            default:
                throw new IllegalArgumentException(method);
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
                Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS };
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value = null;
            if (column.equals(Root.COLUMN_ROOT_ID) || column.equals(Root.COLUMN_DOCUMENT_ID)) value = "root";
            if (column.equals(Root.COLUMN_TITLE)) value = "Persona QA model source";
            if (column.equals(Root.COLUMN_FLAGS)) value = Root.FLAG_SUPPORTS_IS_CHILD;
            row.add(column, value);
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        List<Entry> documents = new ArrayList<>();
        documents.add(entry(documentId));
        return cursor(projection, documents);
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        List<Entry> children = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (parentDocumentId.equals(entry.parent)) children.add(entry);
        }
        children.sort(Comparator.comparing(entry -> entry.name));
        return cursor(projection, children);
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        Entry current = entries.get(documentId);
        while (current != null && current.parent != null) {
            if (current.parent.equals(parentDocumentId)) return true;
            current = entries.get(current.parent);
        }
        return false;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new UnsupportedOperationException("Read-only fixture provider");
        if (signal != null) signal.throwIfCanceled();
        Entry entry = entry(documentId);
        opens.incrementAndGet();
        if (entry.fail) throw new FileNotFoundException("Injected provider failure");
        if (entry.descriptor == null) throw new FileNotFoundException("Not a file");
        ParcelFileDescriptor input;
        try {
            input = entry.descriptor.dup();
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            Thread writer = new Thread(() -> streamFile(input, pipe[1], entry.slow), "SAF-fixture-writer");
            writer.setDaemon(true);
            writer.start();
            return pipe[0];
        } catch (IOException error) {
            try { input.close(); } catch (IOException ignored) { }
            throw new FileNotFoundException(error.getMessage());
        }
    }

    private void streamFile(ParcelFileDescriptor input, ParcelFileDescriptor writer, boolean slow) {
        try (ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(input);
             ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(writer)) {
            try {
                byte[] buffer = new byte[slow ? 8192 : 65536];
                long offset = 0;
                int count;
                // Positional reads do not change the offset shared by duplicated descriptors.
                while ((count = stream.getChannel().read(ByteBuffer.wrap(buffer), offset)) >= 0) {
                    output.write(buffer, 0, count);
                    offset += count;
                    if (slow) {
                        streamedBytes.addAndGet(count);
                        Thread.sleep(10);
                    }
                }
            } catch (IOException | InterruptedException error) {
                writer.closeWithError("Fixture reader closed");
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        } catch (IOException ignored) {
            // A cancelled reader closes the pipe; all descriptors are owned by this fixture.
        }
    }

    private Entry entry(String id) throws FileNotFoundException {
        Entry entry = entries.get(id);
        if (entry == null) throw new FileNotFoundException(id);
        return entry;
    }

    private MatrixCursor cursor(String[] projection, List<Entry> documents) {
        String[] columns = projection != null ? projection : new String[] {
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_FLAGS };
        MatrixCursor cursor = new MatrixCursor(columns);
        for (Entry entry : documents) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                Object value = null;
                if (column.equals(Document.COLUMN_DOCUMENT_ID)) value = entry.id;
                if (column.equals(Document.COLUMN_DISPLAY_NAME)) value = entry.name;
                if (column.equals(Document.COLUMN_MIME_TYPE)) value = entry.descriptor == null
                        ? Document.MIME_TYPE_DIR : "application/octet-stream";
                if (column.equals(Document.COLUMN_SIZE)) value = entry.size;
                if (column.equals(Document.COLUMN_FLAGS)) value = 0;
                row.add(column, value);
            }
        }
        return cursor;
    }
}
