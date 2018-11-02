package com.example.michael.soundcompass;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Iterator;
import java.util.Vector;

import edu.emory.mathcs.jtransforms.fft.DoubleFFT_1D;

/**
 * Created by Michael on 07.06.2017.
 * Calculates the DOA and the estimated TDOA. Includes all three algorithms despite the name.
 */

public class GCCphat extends AsyncTask<Void, double[], Void> {
    private final int fs; //sampling frequency.
    private final double T; //length of the analyzed samples.
    private final int RECORDER_CHANNELS;
    private int minBufferSize, BufferSize; //in byte units
    public boolean running;
    private double oldangle, oldangleB; //save the last angles for red and blue arrow. Needed for the animation.
    private double[] queue; // saves the last ten calculated TDOAs
    private int type; //defines which algorithm to use: 1 = gcc, 2 = aed, 3 = Proposed.

    private AudioRecord recorder;
    private final TextView text;
    private ImageView redArrow, blueArrow;
    private RotateAnimation anim, anim2;
    private DoubleFFT_1D fft;

    public GCCphat(ImageView myView, ImageView blueArrow, TextView text, double T, int type) {
        fs = 44100;
        this.T = T; //length of the analyzed data.
        RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_STEREO;
        minBufferSize = recorder.getMinBufferSize(fs, RECORDER_CHANNELS, AudioFormat.ENCODING_PCM_16BIT);
        redArrow = myView; this.blueArrow = blueArrow;
        BufferSize = (int) (fs*T)*2; //2 channels
        BufferSize -= BufferSize%4; //Buffersize needs to be a multiple of 4
        this.text = text;
        fft = new DoubleFFT_1D(BufferSize/2);
        running = false;
        oldangle = 0; oldangleB = 0;
        queue = new double[10];
        this.type = type;
    }

    @Override
    protected void onPreExecute() {
        running = true;
    }

    @Override
    protected Void doInBackground(Void... arg0) {
        try {
            if (recorder != null)
                recorder.release();
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, fs, RECORDER_CHANNELS,
                    AudioFormat.ENCODING_PCM_16BIT, BufferSize);
            // if T is chosen too small, correct that mistake.
            if (minBufferSize > BufferSize)
                BufferSize = minBufferSize;
            short[] Data = new short[BufferSize];
            recorder.startRecording();
            while (running) {
                recorder.read(Data, 0, BufferSize);
                int tau_idx = 0;
                if (type == 1) {
                    tau_idx = -gccphat(Data);
                } else if (type == 2) {
                    tau_idx = -aed(Data);
                } else if (type == 3) {
                    tau_idx = -theory(Data);
                }
                double angle = matchAngle(tau_idx);
                double[] a = {angle};
                publishProgress(a);
                // WriteWav.copy2wav(Data, 2);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onProgressUpdate(double[]... params) {
        text.setText(java.util.Arrays.toString(params[0]));
        anim = new RotateAnimation((float) oldangle, -(float) params[0][0], Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0.5f);
        anim.setDuration(300);
        anim.setRepeatCount(0);
        anim.setFillAfter(true);
        redArrow.setAnimation(anim);
        oldangle = -params[0][0];
        double a = pop(-params[0][0]);
        anim2 = new RotateAnimation((float) oldangleB, (float) a, Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0.5f);
        anim2.setDuration(300);
        anim2.setRepeatCount(0);
        anim2.setFillAfter(true);
        try {
            blueArrow.setAnimation(anim2);
        } catch (Exception e) {
            e.printStackTrace();
        }
        oldangleB = a;
    }

    @Override
    protected void onCancelled() {
        running = false;
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    //use this to switch between the algorithms.
    public void setMode(int mode) {
        type = mode;
    }

    //GCC-PHAT
    public int gccphat(short[] Data) {
        int channelLength = Data.length/2; //data per channel.
        double[][] channelSplit = separateChannels(Data);
        double[] X1 = new double[channelLength*2], X2 = new double[channelLength*2];
        System.arraycopy(channelSplit[0], 0, X1, 0, channelLength); // need space for the complex parts
        System.arraycopy(channelSplit[1], 0, X2, 0, channelLength);
        fft.realForwardFull(X1);
        fft.realForwardFull(X2);
        conj(X2);
        double[] X = Cmultiply(X1, X2);
        Cnormalize(X);
        fft.complexInverse(X, false);
        int tau_idx = argmax(X, 2);
        if (tau_idx > channelLength)  // when tau greater than half the length, it is negative.
            tau_idx -= 2*channelLength;
        return tau_idx/2;
    }

    //Proposed TDE-Algorithm
    public int theory(short[] Data) {
        //separate channels
        int channelLength = Data.length/2; //data per channel.
        double[][] channelSplit = separateChannels(Data);
        double[] X1 = new double[channelLength*2], X2 = new double[channelLength*2];
        System.arraycopy(channelSplit[0], 0, X1, 0, channelLength); // need space for the complex parts
        System.arraycopy(channelSplit[1], 0, X2, 0, channelLength);
        fft.realForwardFull(X1);
        fft.realForwardFull(X2);
        double[] h2 = new double[channelLength], H2 = new double[2*channelLength];
        h2[channelLength/2] = 1;
        System.arraycopy(h2, 0, H2, 0, channelLength);
        fft.realForward(H2);
        //X1*H2/X2
        Cinverse(X2);
        double[] h1 = Cmultiply(Cmultiply(X1,H2), X2);
        fft.complexInverse(h1, false);
        //h1 should be real.
        int tau_idx = (argmax(h1, 2)/2 - argmax(h2, 1)); //h1 is complex and twice as long as h2.
        return tau_idx;
    }

    //AED
    public int aed(short[] Data) {
        //parameter
        double mu = .001;
        //separate channels
        int channelLength = Data.length/2; //data per channel.
        double[][] channelSplit = separateChannels(Data);
        double[] x = new double[2*channelLength];
        System.arraycopy(channelSplit[0], 0, x, 0, channelLength);
        System.arraycopy(channelSplit[1], 0, x, channelLength, channelLength);
        //h1 and h2 are real. retain only the real parts and store them in u.
        double[] u = new double[2*channelLength];
        u[0] = Math.sqrt(2)/2; u[channelLength] = -Math.sqrt(2)/2;
        double e = Rmultiply(u, x);
        double olde = e+1;
        int iteration = 1;
        while (Math.abs(Math.abs(olde) - Math.abs(e)) >= 0.001 && iteration < 100) {
            u = Rsubtract(u,Rmultiply(e,x));
            Rnormalize(u);
            olde = e;
            e = Rmultiply(u,x);
        }
        double[] h1 = new double[channelLength]; double[] h2 = new double[channelLength];
        System.arraycopy(u, channelLength, h1, 0, channelLength); //eigentlich -u, wird aber nicht akzeptiert und wir nehmen in der nächsten zeile sowieso abs.
        System.arraycopy(u, 0, h2, 0, channelLength);
        int tau_idx = (argmax(h1, 1) - argmax(h2, 1));
        return tau_idx;
    }

    //teilt audiospur in channels auf und konvertiert sie ausserdem von short zu double.
    public double[][] separateChannels(short[] Data) {
        //audiofile in bytes ist wie folgt gegliedert: 001100110011... (sample für jeweiligen channel), 010101 für short.
        double[][] splitted = new double[2][Data.length/2];
        for (int i = 0; i<Data.length; i+=2) {
            splitted[0][i/2] = Data[i];
            splitted[1][i/2] = Data[i+1];
        }
        return splitted;
    }

    //calculates Complex conjugate of complex number.
    public void conj(double[] Data) {
        for (int i = 0; i<Data.length; i+=2)
            Data[i + 1] = -Data[i + 1];
    }

    //multiplies two complex numbers
    public double[] Cmultiply(double[] x1, double[] x2) {
        double[] x = new double[x1.length];
        for (int i=0; i<x1.length; i+=2) {
            x[i] = x1[i]*x2[i] - x1[i+1]*x2[i+1];
            x[i+1] = x1[i+1]*x2[i] + x1[i]*x2[i+1];
        }
        return x;
    }

    //normalizes a complex number
    public void Cnormalize(double[] x) {
        for (int i=0; i<x.length; i+=2) {
            double mag = Math.sqrt(x[i]*x[i] + x[i+1]*x[i+1]);
            x[i] = x[i] / mag;
            x[i + 1] = x[i + 1] / mag;
        }
    }

    //calculates the reciprocal value of a complex number
    public void Cinverse(double[] Data) {
        for (int i = 0; i<Data.length; i+=2) {
            double mag = Data[i]*Data[i]+Data[i+1]*Data[i+1];
            Data[i+1] = -Data[i+1]/mag;
            Data[i] = Data[i]/mag;
        }
    }

    //normalizes a series of real numbers
    public void Rnormalize(double[] Data) {
        double mag = 0;
        for (int i = 0; i<Data.length; i++)
            mag += Data[i]*Data[i];
        mag = Math.sqrt(mag);
        for (int i = 0; i<Data.length; i++)
            Data[i] /= mag;
    }

    //subtracts two real arrays from each other
    public double[] Rsubtract(double[] x1, double[] x2) {
        double[] output = new double[x1.length];
        for (int i = 0; i<x1.length; i++)
            output[i] = x1[i] - x2[i];
        return output;
    }

    //multiplies two real arras with each other, then takes the sum.
    public double Rmultiply(double[] x1, double[] x2) {
        double sum = 0;
        for (int i = 0; i < x1.length; i ++)
            sum += x1[i] * x2[i];
        return sum;
    }

    //multiplication of a real array with a real number
    public double[] Rmultiply(double x1, double[] x2) {
        double[] output = new double[x2.length];
        for (int i = 0; i<x2.length; i++)
            output[i] = x1*x2[i];
        return output;
    }

    //argamx of array. step = 1 for real arrays, step = 2 for complex ones.
    protected int argmax(double[] Data, int step) {
        double max = 0; int arg = 0;
        for(int i=0; i<Data.length; i+=step) {
            if (Math.abs(Data[i]) > max) {
                max = Math.abs(Data[i]);
                arg = i;
            }
        }
        return arg;
    }

    //matches TDOA to DOA.
    protected double matchAngle(int idx) {
//        double[] angles = {0, 3.4000, 6.8000, 10.3000, 13.7000, 17.3000, 20.9000,
//                24.6000, 28.4000, 32.3000, 36.4000, 40.8000, 45.4000, 50.5000, 56.2000,
//                62.9000, 71.8000, 90.0000};
//        double[] angles = {0, 0.2000, 3.1000, 6.000, 8.8000, 11.7000, 14.6000, 17.6000,
//                20.6000, 23.7000, 26.8000, 30.0000, 33.4000, 36.8000, 40.5000, 44.3000,
//                48.4000, 52.9000, 57.9000, 63.7000, 71.1000, 84.7000, 90.0000}; not sure what i did here, looks like wrong calculus
        double[] angles = { 0, 2.8000, 5.5000, 8.3000, 11.1000, 14.0000, 16.8000, 19.7000, 22.7000,
                25.7000, 28.8000, 32.0000, 35.4000, 38.8000, 42.5000, 46.3000, 50.5000, 55.1000,
                60.2000, 66.4000, 74.7000, 90.000 };
        int N = angles.length-1;
        if (idx > N) {
            idx = N;
        }else if (idx < -N) {
            idx = -N;
        }
        if (idx < 0)
            return -angles[-idx];
        return angles[idx];
    }

    // manages queue: adds last element at end and moves the rest by one position.
    protected double pop(double newangle) {
        double max = newangle, min = newangle;
        double mean = 0;
        for (int i = 0; i < queue.length-1; i++) {
            queue[i] = queue[i + 1];
            //mean += queue[i];
            //if (queue[i] > max)
            //        max = queue[i];
            //if (queue[i] < min)
            //        min = queue[i];
        }
        queue[queue.length-1] = newangle;
        //mean += queue[queue.length-1];
        //mean /= queue.length;
        //double[] r = {min, max};
        mean = count();
        return mean;
    }

    //returns the value of the angle that occurs most in queue.
    double count() {
        Vector<Double> votes = new Vector<Double>();
        Vector<Double> cand = new Vector<Double>();
        //collect votes
        for (double i : queue) {
            if (cand.contains(i)) {
                int index = cand.indexOf(i);
                votes.set(index, votes.get(index)+1);
            } else {
                votes.add(1.0d);
                cand.add(i);
            }
        }
        double max = 0;
        //search angle with most votes.
        for (Iterator<Double> it = votes.iterator(); it.hasNext();) {
            double a = it.next();
            if (a > max)
                max = a;
        }
        return cand.get(votes.indexOf(max));
    }
}
