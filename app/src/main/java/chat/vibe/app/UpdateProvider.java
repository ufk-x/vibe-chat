package chat.vibe.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import chat.vibe.app.core.UpdateArtifact;

/** Each immutable APK has its own URI; old release.apk grants cannot open a newer file. */
public final class UpdateProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File file(Uri uri){String name=uri.getLastPathSegment();if(!"content".equals(uri.getScheme())||!"chat.vibe.app.updates".equals(uri.getAuthority())||uri.getPathSegments().size()!=1||uri.getQuery()!=null||uri.getFragment()!=null||!UpdateArtifact.validName(name))throw new SecurityException("Invalid update URI");return new File(getContext().getFilesDir(),"updates/"+name);}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new SecurityException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    public String getType(Uri uri){file(uri);return "application/vnd.android.package-archive";}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){File f=file(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(cols,1);Object[] values=new Object[cols.length];for(int i=0;i<cols.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(cols[i])?f.getName():OpenableColumns.SIZE.equals(cols[i])?f.length():null;c.addRow(values);return c;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
