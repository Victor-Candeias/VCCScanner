package pt.vcc.scanner;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.*;

/** Four handles in clockwise order, expressed in normalized image coordinates. */
public class CropView extends View {
    private static final float[] DEFAULT_CORNERS={.04f,.04f,.96f,.04f,.96f,.96f,.04f,.96f};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame=new RectF();
    // Preallocated: onDraw runs on every drag frame and must not allocate.
    private final Path path=new Path();
    private final float[] points=DEFAULT_CORNERS.clone();
    private Bitmap bitmap;
    private int active=-1;
    public CropView(Context context,Bitmap bitmap){super(context);this.bitmap=bitmap;describe();}
    /** Inflation constructors the layout tools require; the page to crop is supplied by setBitmap. */
    public CropView(Context context){super(context);describe();}
    public CropView(Context context,AttributeSet attrs){super(context,attrs);describe();}
    public CropView(Context context,AttributeSet attrs,int defStyleAttr){super(context,attrs,defStyleAttr);describe();}
    private void describe(){setContentDescription(getContext().getString(R.string.cd_crop_view));}
    /** Sets the page to crop and resets the handles to the default inset. */
    public void setBitmap(Bitmap value){bitmap=value;System.arraycopy(DEFAULT_CORNERS,0,points,0,points.length);invalidate();}
    /** Places the handles on the given normalized corners, clockwise from the top left. */
    public void setCorners(float[] value){if(value==null||value.length!=points.length)return;System.arraycopy(value,0,points,0,points.length);invalidate();}
    public float[] corners(){return points.clone();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(bitmap==null)return;
        float scale=Math.min((getWidth()-40f)/bitmap.getWidth(),(getHeight()-40f)/bitmap.getHeight());float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;frame.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);canvas.drawBitmap(bitmap,null,frame,paint);
        path.rewind();for(int i=0;i<4;i++){float x=frame.left+points[i*2]*w,y=frame.top+points[i*2+1]*h;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();paint.setColor(0x40176B58);canvas.drawPath(path,paint);paint.setColor(0xff176B58);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);for(int i=0;i<4;i++){paint.setColor(Color.WHITE);canvas.drawCircle(frame.left+points[i*2]*w,frame.top+points[i*2+1]*h,14,paint);paint.setColor(0xff176B58);canvas.drawCircle(frame.left+points[i*2]*w,frame.top+points[i*2+1]*h,9,paint);}
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event){
        if(bitmap==null||frame.width()<=0||frame.height()<=0)return false;
        if(event.getAction()==MotionEvent.ACTION_DOWN){float best=Float.MAX_VALUE;for(int i=0;i<4;i++){float dx=event.getX()-(frame.left+points[i*2]*frame.width()),dy=event.getY()-(frame.top+points[i*2+1]*frame.height());float distance=dx*dx+dy*dy;if(distance<best){active=i;best=distance;}}getParent().requestDisallowInterceptTouchEvent(true);return true;}
        if(event.getAction()==MotionEvent.ACTION_MOVE&&active>=0){points[active*2]=Math.max(0,Math.min(1,(event.getX()-frame.left)/frame.width()));points[active*2+1]=Math.max(0,Math.min(1,(event.getY()-frame.top)/frame.height()));invalidate();return true;}
        if(event.getAction()==MotionEvent.ACTION_UP||event.getAction()==MotionEvent.ACTION_CANCEL){active=-1;performClick();return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
