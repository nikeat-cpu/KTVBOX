package com.github.tvbox.osc.server;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * @author pj567
 * @date :2021/1/5
 * @description: 资源文件加载
 */
public class RawRequestProcess implements RequestProcess {
    private Context mContext;
    private String fileName;
    private int resourceId;
    private String mimeType;

    public RawRequestProcess(Context context, String fileName, int resourceId, String mimeType) {
        this.mContext = context;
        this.fileName = fileName;
        this.resourceId = resourceId;
        this.mimeType = mimeType;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return session.getMethod() == NanoHTTPD.Method.GET && this.fileName.equalsIgnoreCase(fileName);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        InputStream inputStream = null;
        try {
            // 注意：不能用 available() 作为长度——APK 内 res/raw 资源被压缩存储时 available() 返回 0，导致响应 0 字节
            inputStream = mContext.getResources().openRawResource(this.resourceId);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            inputStream.close();
            byte[] data = baos.toByteArray();
            return RemoteServer.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mimeType + "; charset=utf-8",
                    new java.io.ByteArrayInputStream(data), (long) data.length);
        } catch (IOException IOExc) {
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + IOExc.getMessage());
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}