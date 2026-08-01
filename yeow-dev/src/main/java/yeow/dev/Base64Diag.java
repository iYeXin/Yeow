package yeow.dev;
import com.whl.quickjs.wrapper.QuickJSContext;
public class Base64Diag {
    public static void main(String[] a) {
        try(var c=QuickJSContext.create()){
            var r=c.evaluate(
                "function dec(s){var v=new Uint8Array(Base64ToUint8Array(s));var b=[];for(var i=0;i<v.length;i++)b.push(v[i]);return JSON.stringify(b);}"+
                "JSON.stringify({aaaa:dec('AAAA'),aaab:dec('AAAB'),aabb:dec('AABB'),abcd:dec('ABCD')});",
                "t.js");
            System.out.println(r);
        }catch(Exception e){e.printStackTrace();}
    }
}
