package yeow.dev;

import com.whl.quickjs.wrapper.QuickJSContext;

public class Base64Test {
    public static void main(String[] argv) {
        try (var ctx = QuickJSContext.create()) {
            if (ctx == null) { System.err.println("ctx null"); return; }

            // Direct ArrayBuffer test (no base64)
            var r = ctx.evaluate(
                "var ab = new ArrayBuffer(5);" +
                "var v = new Uint8Array(ab);" +
                "v[0]=72; v[1]=101; v[2]=108; v[3]=108; v[4]=111;" +
                "JSON.stringify({len: v.length, bytes: Array.from(v)});",
                "t.js");
            System.out.println("Direct AB write/read: " + r);

            // Verify encode output as raw string
            r = ctx.evaluate(
                "var buf = new Uint8Array([72,101,108,108,111]).buffer;" +
                "var b64 = Uint8ArrayToBase64(buf);" +
                "b64;",
                "t.js");
            System.out.println("Encode raw: " + r);

            // Verify encode output bytes match Node.js
            r = ctx.evaluate(
                "JSON.stringify({b64: Uint8ArrayToBase64(new Uint8Array([0,1,2,255,254,253]).buffer)});",
                "t.js");
            System.out.println("Encode binary: " + r);

            // Decode with diagnostic
            r = ctx.evaluate(
                "function diag(s) {" +
                "  var ab = Base64ToUint8Array(s);" +
                "  var v = new Uint8Array(ab);" +
                "  return JSON.stringify({in:s, len:ab.byteLength, bytes:Array.from(v)});" +
                "}" +
                "JSON.stringify({SGVsbG8: diag('SGVsbG8='), aaaa: diag('AAAA'), aaab: diag('AAAB')});",
                "t.js");
            System.out.println("Decode diag: " + r);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
