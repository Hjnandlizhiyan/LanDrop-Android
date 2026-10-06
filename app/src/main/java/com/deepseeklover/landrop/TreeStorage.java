package com.deepseeklover.landrop;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.*;

/** SAF keeps receiving inside the directory explicitly chosen by the user. */
final class TreeStorage implements LanDropEngine.Storage {
    private final ContentResolver resolver;
    private final Uri tree;
    TreeStorage(ContentResolver resolver, Uri tree) throws Exception {
        this.resolver = resolver; this.tree = tree;
        Uri root = root();
        try (Cursor cursor = resolver.query(root, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || (cursor.getInt(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) throw new IOException("接收文件夹不可写，请重新选择");
        }
    }
    // Recovery runs only before the receiving service starts, never while a folder picker returns.
    void cleanupAbandoned() {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                if (cursor.getString(1).matches("\\.landrop-[a-f0-9-]{36}\\.part")) {
                    try { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
    }
    private Uri root() { return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); }
    public String label() {
        try (Cursor cursor = resolver.query(root(), new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "所选接收文件夹";
    }
    public synchronized LanDropEngine.Sink create(String name, String id) throws Exception {
        String clean = LanDropEngine.safeName(name);
        // Keep unfinished bytes hidden from the final filename until the full stream arrives.
        Uri partial = DocumentsContract.createDocument(resolver, root(), "application/octet-stream", ".landrop-" + id + ".part");
        if (partial == null) throw new IOException("无法创建接收文件");
        return new LanDropEngine.Sink() {
            private Uri current = partial;
            public OutputStream open() throws Exception {
                OutputStream output = resolver.openOutputStream(current, "w");
                if (output == null) throw new IOException("无法写入接收文件夹"); return output;
            }
            public String commit() throws Exception {
                String chosen = uniqueName(clean);
                Uri renamed = DocumentsContract.renameDocument(resolver, current, chosen);
                if (renamed == null) throw new IOException("接收文件夹不支持完成文件保存，请选择本机文件夹");
                current = renamed; return current.toString();
            }
            public void abort() { try { DocumentsContract.deleteDocument(resolver, current); } catch (Exception ignored) { } }
        };
    }
    private synchronized String uniqueName(String name) throws Exception {
        Set<String> names = new HashSet<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取接收文件夹");
            while (cursor.moveToNext()) names.add(cursor.getString(0));
        }
        int dot = name.lastIndexOf('.'); String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; i < 10000; i++) { String candidate = stem + (i == 0 ? "" : " (" + i + ")") + ext; if (!names.contains(candidate)) return candidate; }
        throw new IOException("同名文件过多");
    }
}
