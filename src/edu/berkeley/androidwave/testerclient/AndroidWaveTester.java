// 
//  AndroidWaveTester.java
//  AndroidWaveTesterClient
//  
//  Created by Philip Kuryloski on 2011-06-21.
//  Copyright 2011 University of California, Berkeley. All rights reserved.
// 

package edu.berkeley.androidwave.testerclient;

import edu.berkeley.androidwave.waveclient.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

public class AndroidWaveTester extends Activity
{

    private static final String TAG = AndroidWaveTester.class.getSimpleName();
    
    private static final String ACTION_WAVE_SERVICE = "edu.berkeley.androidwave.intent.action.WAVE_SERVICE";
    private static final String ACTION_DID_AUTHORIZE = "edu.berkeley.androidwave.intent.action.DID_AUTHORIZE";
    private static final String ACTION_DID_DENY = "edu.berkeley.androidwave.intent.action.DID_DENY";
    private static final int REQUEST_CODE_AUTH = 1;
    private final String API_KEY = "cdaeoicdeaoixtrchearhc,h.bmte";
    private final String TRACE_NAME = "AndroidWaveTester";
    
    private IWaveServicePublic mWaveService;
    private boolean mBound;
    
    protected String[] recipe_ids = { "edu.berkeley.waverecipe.passthrough.AccelerometerPassThrough" };
    protected String chosenRecipeId;
    protected boolean shouldTrace;
    
    private boolean testRunning;
    
    // UI Outlets
    private Button startButton;
    private CheckBox traceCheckBox;
    private TextView messageTextView;
    
    // received data stats
    private long startTime;
    private long stopTime;
    private long firstMessageTime;
    private long lastMessageTime;
    private int messageCount;
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        startButton = (Button) findViewById(R.id.start_button);
        traceCheckBox = (CheckBox) findViewById(R.id.trace_checkbox);
        messageTextView = (TextView) findViewById(R.id.message_textview);
        
        testRunning = false;
        
        // initialize stats
        startTime = 0;
        stopTime = 0;
        firstMessageTime = 0;
        lastMessageTime = 0;
        messageCount = 0;
        
        // configure checkbox
        shouldTrace = false;
        traceCheckBox.setChecked(shouldTrace);
        traceCheckBox.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                shouldTrace = ((CheckBox) v).isChecked();
            }
        });
        
        // disable the button until we have connected to the service
        startButton.setEnabled(false);
        
        // connect to the service
        Intent i = new Intent(ACTION_WAVE_SERVICE);
        if (bindService(i, mConnection, Context.BIND_AUTO_CREATE)) {
            mBound = true;
            Toast.makeText(AndroidWaveTester.this, "Connected to WaveService", Toast.LENGTH_SHORT).show();
        } else {
            Log.d(getClass().getSimpleName(), "Could not bind with "+i);
            // TODO: replace this Toast with a dialog that allows quitting
            Toast.makeText(AndroidWaveTester.this, "Could not connect to the WaveService!", Toast.LENGTH_SHORT).show();
            messageTextView.setText("ERROR:\n\nFailed to bind to the WaveService.\n\nIs AndroidWave installed on this device?\n\nPlease address this issue and restart this Application.");
        }
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        
        if (testRunning) {
            startButton.performClick();
        }

        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }
    
    private void startTest() {
        testRunning = true;
        
        messageTextView.setText("Test started...");

        try {
            WaveRecipeAuthorizationInfo authInfo = mWaveService.retrieveAuthorizationInfo(API_KEY, chosenRecipeId);
            messageTextView.setText("Test started...\n\nExpected Data Rate: "+authInfo.outputMaxRate+"Hz");
        } catch (RemoteException re) {
            Log.d(TAG, "lost connection to the service", re);
            Toast.makeText(AndroidWaveTester.this, "Lost connection to WaveService", Toast.LENGTH_SHORT).show();
        }

        // make the start button a stop button
        startButton.setOnClickListener(stopButtonListener);
        startButton.setText("Stop");
        
        if (shouldTrace) {
            try {
                Debug.startMethodTracing(TRACE_NAME);
            } catch (Exception e) {
                Log.d(TAG, "Exception while Debug.startMethodTracing(...)", e);
            }
        }
        
        startTime = SystemClock.elapsedRealtime();

        try {
            boolean didRegister = mWaveService.registerRecipeOutputListener(API_KEY, chosenRecipeId, outputListener);
            if (!didRegister) {
                Toast.makeText(this, "Error requesting recipe data stream.", Toast.LENGTH_SHORT).show();
            }
        } catch (RemoteException re) {
            Log.d(TAG, "lost connection to the service", re);
            Toast.makeText(this, "Lost connection to WaveService", Toast.LENGTH_SHORT).show();
        }

        // enable the "start" button (which is now a stop button)
        startButton.setEnabled(true);
    }
    
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_AUTH) {
            if (resultCode == RESULT_OK) {
                if (data.getAction().equals(ACTION_DID_AUTHORIZE)) {
                    startTest();
                } else {
                    Toast.makeText(this, "Authorization Denied!", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Authorization process canceled.", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    private OnClickListener startButtonListener = new OnClickListener() {
        public void onClick(View v) {
            traceCheckBox.setEnabled(false);
            startButton.setEnabled(false);
            
            // let the user choose a recipe
            AlertDialog.Builder builder = new AlertDialog.Builder(AndroidWaveTester.this);
            builder.setTitle("Select Recipe");
            builder.setItems(recipe_ids, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int item) {
                    chosenRecipeId = recipe_ids[item];
                    
                    try {
                        if (mWaveService.isAuthorized(API_KEY, chosenRecipeId)) {
                            startTest();
                        } else {
                            // get an auth intent from the service
                            Intent i = mWaveService.getAuthorizationIntent(chosenRecipeId, API_KEY);
            
                            // then run it looking for a result
                            try {
                                startActivityForResult(i, REQUEST_CODE_AUTH);
                            } catch (ActivityNotFoundException anfe) {
                                anfe.printStackTrace();
                                Toast.makeText(AndroidWaveTester.this, "Error launching authorization UI", Toast.LENGTH_SHORT).show();
                            }
                        }
                    } catch (RemoteException re) {
                        Log.d(TAG, "lost connection to the service", re);
                        Toast.makeText(AndroidWaveTester.this, "Lost connection to WaveService", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            AlertDialog alert = builder.show();
        }
    };
    
    private OnClickListener stopButtonListener = new OnClickListener() {
        public void onClick(View v) {
            // shut down the test
            stopTime = SystemClock.elapsedRealtime();
            try {
                mWaveService.unregisterRecipeOutputListener(API_KEY, chosenRecipeId);
            } catch (RemoteException re) {
                Log.d(TAG, "lost connection to the service", re);
                Toast.makeText(AndroidWaveTester.this, "Lost connection to WaveService", Toast.LENGTH_SHORT).show();
            }
            startButton.setEnabled(false);
            
            // calculate additional stats
            long startStopTime = stopTime - startTime;
            long firstDelay = firstMessageTime - startTime;
            
            // print out output
            String messageText = "";
            messageText += "Test stopped.\n";
            messageText += "\n";
            messageText += "You chose recipe "+chosenRecipeId+"\n";
            messageText += "\n";
            messageText += ""+messageCount+" data samples were received in "+(startStopTime/1000.0)+" seconds, ";
            messageText += "yielding an average rate of "+(1000.0*messageCount/startStopTime)+"Hz.\n";
            
            messageTextView.setText(messageText);

            if (shouldTrace) {
                try {
                    Debug.stopMethodTracing();
                } catch (Exception e) {
                    Log.d(TAG, "Exception while Debug.stopMethodTracing()", e);
                }
            }
            
            testRunning = false;
        }
    };
    
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            mWaveService = IWaveServicePublic.Stub.asInterface(service);
            
            // enable the button now that the service is connected
            startButton.setEnabled(true);
            startButton.setOnClickListener(startButtonListener);
        }
        
        public void onServiceDisconnected(ComponentName className) {
            mWaveService = null;
            Toast.makeText(AndroidWaveTester.this, "WaveService disconnected", Toast.LENGTH_SHORT).show();
        }
    };
    
    private IWaveRecipeOutputDataListener outputListener = new IWaveRecipeOutputDataListener.Stub() {
        public void receiveWaveRecipeOutputData(ParcelableWaveRecipeOutputData wrOutput) {
            
            messageCount++;
            lastMessageTime = SystemClock.elapsedRealtime();
            if (firstMessageTime == 0) {
                firstMessageTime = lastMessageTime;
            }
        }
    };
    
    public boolean isBound() {
        return (mBound && (mWaveService != null));
    }
}
