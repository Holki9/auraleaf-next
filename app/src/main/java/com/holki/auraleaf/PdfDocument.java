package com.holki.auraleaf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

public class PdfDocument implements ComicDocument {
    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;
    private int pageCount = 0;

    public PdfDocument(ParcelFileDescriptor pfd) throws Exception {
        this.pfd = pfd;
        this.renderer = new PdfRenderer(pfd);
        this.pageCount = renderer.getPageCount();
    }

    @Override
    public int getPageCount() {
        return pageCount;
    }

    @Override
    public synchronized Bitmap renderPage(int index, int maxW, int maxH) {
        if (index < 0 || index >= pageCount || renderer == null) return null;
        try {
            PdfRenderer.Page page = renderer.openPage(index);
            float pageW = page.getWidth();
            float pageH = page.getHeight();

            // Supersample at 1.5x of target resolution using RENDER_MODE_FOR_PRINT
            // for razor-sharp vector line art, crisp text, and lossless image quality
            float baseScale = Math.min((float) maxW / pageW, (float) maxH / pageH);
            float scale = Math.max(1.0f, baseScale * 1.5f);

            int drawW = Math.max(10, Math.round(pageW * scale));
            int drawH = Math.max(10, Math.round(pageH * scale));

            // Cap at 3840px max dimension to prevent out-of-memory while retaining 4K sharpness
            if (drawW > 3840 || drawH > 3840) {
                float cap = 3840f / Math.max(drawW, drawH);
                drawW = Math.round(drawW * cap);
                drawH = Math.round(drawH * cap);
            }

            Bitmap bmp = Bitmap.createBitmap(drawW, drawH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);

            // RENDER_MODE_FOR_PRINT: Lossless print-grade rasterization!
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            page.close();
            return bmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public synchronized float getPageAspectRatio(int index) {
        if (index < 0 || index >= pageCount || renderer == null) return 1.414f;
        try {
            PdfRenderer.Page page = renderer.openPage(index);
            float ratio = (float) page.getHeight() / Math.max(1f, page.getWidth());
            page.close();
            return ratio;
        } catch (Exception e) {
            return 1.414f;
        }
    }

    @Override
    public synchronized void close() {
        if (renderer != null) {
            try { renderer.close(); } catch (Exception ignored) {}
            renderer = null;
        }
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
            pfd = null;
        }
    }
}
