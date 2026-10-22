package moe.shizuku.api;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/** Shizuku binder 传递容器——与 Shizuku 服务端线格式兼容（writeStrongBinder）。
 *  包名/类名必须一致：Bundle 跨进程序列化按类名反查 CREATOR。 */
public class BinderContainer implements Parcelable {
 public IBinder binder;
 public BinderContainer(IBinder binder){ this.binder = binder; }
 protected BinderContainer(Parcel in){ this.binder = in.readStrongBinder(); }
 public int describeContents(){ return 0; }
 public void writeToParcel(Parcel dest, int flags){ dest.writeStrongBinder(this.binder); }
 public static final Creator<BinderContainer> CREATOR = new Creator<BinderContainer>(){
  public BinderContainer createFromParcel(Parcel source){ return new BinderContainer(source); }
  public BinderContainer[] newArray(int size){ return new BinderContainer[size]; }
 };
}
