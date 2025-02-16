package plugin.google.maps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v4.content.PermissionChecker;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class PluginLocationService extends CordovaPlugin {
  private Activity activity;
  private final String TAG = "PluginLocationService";
  private HashMap<String, Bundle> bufferForLocationDialog = new HashMap<String, Bundle>();

  private final int ACTIVITY_LOCATION_DIALOG = 0x7f999900; // Invite the location dialog using Google Play Services
  private final int ACTIVITY_LOCATION_PAGE = 0x7f999901;   // Open the location settings page

  private GoogleApiClient googleApiClient = null;

  public void initialize(final CordovaInterface cordova, final CordovaWebView webView) {
    super.initialize(cordova, webView);
    activity = cordova.getActivity();
  }
  private static Location lastLocation = null;
  private ArrayList<CallbackContext> regularAccuracyRequestList = new ArrayList<CallbackContext>();
  private ArrayList<CallbackContext> highAccuracyRequestList = new ArrayList<CallbackContext>();
  public static final Object semaphore = new Object();

  public static void setLastLocation(Location location) {
    // Sets the last location if the end user click on the mylocation (blue dot)
    lastLocation = location;
  }


  @Override
  public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {

    cordova.getThreadPool().submit(new Runnable() {
      @Override
      public void run() {
        try {
          if ("getMyLocation".equals(action)) {
            PluginLocationService.this.getMyLocation(args, callbackContext);
          } else if ("hasPermission".equals(action)) {
            PluginLocationService.this.hasPermission(args, callbackContext);
          }

        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    });
    return true;

  }



  @SuppressWarnings("unused")
  public void hasPermission(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
    synchronized (semaphore) {
      // Check geolocation permission.
      boolean locationPermission = PermissionChecker.checkSelfPermission(cordova.getActivity().getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PermissionChecker.PERMISSION_GRANTED;
      callbackContext.success(locationPermission ? 1 : 0);
    }
  }


      @SuppressWarnings("unused")
  public void getMyLocation(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
    synchronized (semaphore) {



      LocationManager locationManager = (LocationManager) this.activity.getSystemService(Context.LOCATION_SERVICE);
      List<String> providers = locationManager.getAllProviders();
      int availableProviders = 0;
      Iterator<String> iterator = providers.iterator();
      String provider;
      boolean isAvailable;
      while(iterator.hasNext()) {
        provider = iterator.next();
        if ("passive".equals(provider)) {
          continue;
        }
        isAvailable = locationManager.isProviderEnabled(provider);
        if (isAvailable) {
          availableProviders++;
        }
        //if (mPluginLayout != null && mPluginLayout.isDebug) {
        //}
      }
      if (availableProviders == 0) {
        JSONObject result = new JSONObject();
        try {
          result.put("status", false);
          result.put("error_code", "not_available");
          result.put("error_message", PluginUtil.getPgmStrings(activity,"pgm_no_location_providers"));
        } catch (JSONException e) {
          e.printStackTrace();
        }
        callbackContext.error(result);
        return;
      }





      JSONObject params = args.getJSONObject(0);
      boolean requestHighAccuracy = false;
      if (params.has("enableHighAccuracy")) {
        requestHighAccuracy = params.getBoolean("enableHighAccuracy");
      }
      if (requestHighAccuracy && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        JSONObject result = new JSONObject();
        try {
          result.put("status", false);
          result.put("error_code", "not_available");
          result.put("error_message", PluginUtil.getPgmStrings(activity,"pgm_no_location_service_is_disabled"));
        } catch (JSONException e) {
          e.printStackTrace();
        }
        callbackContext.error(result);
        return;
      }

      // enableHighAccuracy = true -> PRIORITY_HIGH_ACCURACY
      // enableHighAccuracy = false -> PRIORITY_BALANCED_POWER_ACCURACY
      if (requestHighAccuracy) {
        highAccuracyRequestList.add(callbackContext);
      } else {
        regularAccuracyRequestList.add(callbackContext);
      }

      if (googleApiClient != null && googleApiClient.isConnecting()) {
        return;
      }
    }

    // Request geolocation permission.
    boolean locationPermission = PermissionChecker.checkSelfPermission(cordova.getActivity().getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PermissionChecker.PERMISSION_GRANTED;

    if (!locationPermission) {
      //_saveArgs = args;
      //_saveCallbackContext = callbackContext;
      synchronized (semaphore) {
        cordova.requestPermissions(this, callbackContext.hashCode(), new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        });
        try {
          semaphore.wait();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      locationPermission = PermissionChecker.checkSelfPermission(cordova.getActivity().getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PermissionChecker.PERMISSION_GRANTED;

      if (!locationPermission) {

        PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, PluginUtil.getPgmStrings(activity,"pgm_location_rejected_by_user"));

        synchronized (semaphore) {
          for (CallbackContext callback: regularAccuracyRequestList) {
            callback.sendPluginResult(errorResult);
          }
          for (CallbackContext callback: highAccuracyRequestList) {
            callback.sendPluginResult(errorResult);
          }
          regularAccuracyRequestList.clear();
          highAccuracyRequestList.clear();
        }
        return;
      }
    }
    if (lastLocation != null && Calendar.getInstance().getTimeInMillis() - lastLocation.getTime() <= 2000) {
      //---------------------------------------------------------------------
      // If the user requests the location in two seconds from the last time,
      // return the last result in order to save battery usage.
      // (Don't request the device location too much! Save battery usage!)
      //---------------------------------------------------------------------
      JSONObject result;
      try {
        result = PluginUtil.location2Json(lastLocation);
        result.put("status", true);

        PluginResult successResult = new PluginResult(PluginResult.Status.OK, result);
        synchronized (semaphore) {
          for (CallbackContext callback: regularAccuracyRequestList) {
            callback.sendPluginResult(successResult);
          }
          for (CallbackContext callback: highAccuracyRequestList) {
            callback.sendPluginResult(successResult);
          }
          regularAccuracyRequestList.clear();
          highAccuracyRequestList.clear();
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }

      return;
    }

    if (googleApiClient == null) {

      googleApiClient = new GoogleApiClient.Builder(activity)
        .addApi(LocationServices.API)
        .addConnectionCallbacks(new com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks() {

          @Override
          public void onConnected(Bundle connectionHint) {
            requestLocation();
          }

          @Override
          public void onConnectionSuspended(int cause) {
          }

        })
        .addOnConnectionFailedListener(new com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener() {

          @Override
          public void onConnectionFailed(@NonNull ConnectionResult result) {

            PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, result.toString());

            synchronized (semaphore) {
              for (CallbackContext callback: regularAccuracyRequestList) {
                callback.sendPluginResult(errorResult);
              }
              for (CallbackContext callback: highAccuracyRequestList) {
                callback.sendPluginResult(errorResult);
              }
              regularAccuracyRequestList.clear();
              highAccuracyRequestList.clear();
            }

            googleApiClient.disconnect();
          }

        })
        .build();
      googleApiClient.connect();
    } else if (googleApiClient.isConnected()) {
      requestLocation();
    } else {
      googleApiClient.connect();
    }
  }

  private void requestLocation() {

    if (regularAccuracyRequestList.size() > 0) {
      PluginLocationService.this._requestLocationUpdate(false, false, new CallbackContext("regular-callback", webView) {
        @Override
        public void sendPluginResult(PluginResult pluginResult) {

          synchronized (semaphore) {
            for (CallbackContext callback: regularAccuracyRequestList) {
              callback.sendPluginResult(pluginResult);
            }
            regularAccuracyRequestList.clear();

            if (regularAccuracyRequestList.size() == 0 && highAccuracyRequestList.size() == 0) {
              googleApiClient.disconnect();
            }
          }

        }
      });
    }
    if (highAccuracyRequestList.size() > 0) {
      PluginLocationService.this._requestLocationUpdate(false, true, new CallbackContext("regular-callback", webView) {
        @Override
        public void sendPluginResult(PluginResult pluginResult) {

          synchronized (semaphore) {
            for (CallbackContext callback: highAccuracyRequestList) {
              callback.sendPluginResult(pluginResult);
            }
            highAccuracyRequestList.clear();
            if (regularAccuracyRequestList.size() == 0 && highAccuracyRequestList.size() == 0) {
              googleApiClient.disconnect();
            }
          }

        }
      });
    }
  }


  @SuppressWarnings("MissingPermission")
  private void _requestLocationUpdate(final boolean isRetry, final boolean enableHighAccuracy, final CallbackContext callbackContext) {

    int priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
    if (enableHighAccuracy || "Genymotion".equals(Build.MANUFACTURER)) {
      priority = LocationRequest.PRIORITY_HIGH_ACCURACY;
    }

    if (!isRetry) {
      LocationServices.getFusedLocationProviderClient(cordova.getActivity())
        .getLastLocation()
        .addOnSuccessListener(new OnSuccessListener<Location>() {
          @Override
          public void onSuccess(Location location) {
            lastLocation = location;
            if (lastLocation != null && Calendar.getInstance().getTimeInMillis() - lastLocation.getTime() <= 2000) {
              //---------------------------------------------------------------------
              // If the user requests the location in two seconds from the last time,
              // return the last result in order to save battery usage.
              // (Don't request the device location too much! Save battery usage!)
              //---------------------------------------------------------------------
              JSONObject result;
              try {
                result = PluginUtil.location2Json(lastLocation);
                result.put("status", true);

                PluginResult successResult = new PluginResult(PluginResult.Status.OK, result);
                synchronized (semaphore) {
                  for (CallbackContext callback : regularAccuracyRequestList) {
                    callback.sendPluginResult(successResult);
                  }
                  for (CallbackContext callback: highAccuracyRequestList) {
                    callback.sendPluginResult(successResult);
                  }
                  regularAccuracyRequestList.clear();
                  highAccuracyRequestList.clear();
                }
              } catch (JSONException e) {
                e.printStackTrace();
              }

              googleApiClient.disconnect();
            } else {
              _requestLocationUpdate(true, enableHighAccuracy, callbackContext);
            }
          }
        })
        .addOnFailureListener(new OnFailureListener() {
          @Override
          public void onFailure(@NonNull Exception e) {
            _requestLocationUpdate(true, enableHighAccuracy, callbackContext);
          }
        });
      return;
    }

    // It seems setting the values to both setFastestInterval() and setInterval() is important.
    // https://akira-watson.com/android/fusedlocationproviderapi.html
    LocationRequest locationRequest= LocationRequest.create()
        //.setNumUpdates(2)
        .setFastestInterval(5000)
        .setInterval(10000)
        //.setSmallestDisplacement(0)
        .setPriority(priority)
        .setExpirationDuration(12000)
        .setMaxWaitTime(5000);

    LocationServices.getFusedLocationProviderClient(cordova.getActivity()).requestLocationUpdates(locationRequest, new LocationCallback() {
          @Override
          public void onLocationResult(LocationResult locationResult) {
            Location location = null;
            if (locationResult.getLocations().size() > 0) {
              location = locationResult.getLocations().get(0);
              lastLocation = location;
            } else if (locationResult.getLastLocation() != null) {
              location = locationResult.getLastLocation();
            }

            if (location != null) {
              JSONObject result;
              try {
                result = PluginUtil.location2Json(location);
                result.put("status", true);
                callbackContext.success(result);
              } catch (JSONException e) {
                e.printStackTrace();
              }
            } else {
              // Send back the error result
              JSONObject result = new JSONObject();
              try {
                result.put("status", false);
                result.put("error_code", "cannot_detect");
                result.put("error_message", PluginUtil.getPgmStrings(activity,"pgm_can_not_get_location"));
              } catch (JSONException e) {
                e.printStackTrace();
              }
              callbackContext.error(result);
            }

            googleApiClient.disconnect();
          }
        }, Looper.myLooper());

  }

  private void _onActivityResultLocationPage(Bundle bundle) {
    String callbackId = bundle.getString("callbackId");
    CallbackContext callbackContext = new CallbackContext(callbackId, this.webView);

    LocationManager locationManager = (LocationManager) this.activity.getSystemService(Context.LOCATION_SERVICE);
    List<String> providers = locationManager.getAllProviders();
    int availableProviders = 0;
    //if (mPluginLayout != null && mPluginLayout.isDebug) {
    //}
    Iterator<String> iterator = providers.iterator();
    String provider;
    boolean isAvailable;
    while(iterator.hasNext()) {
      provider = iterator.next();
      if ("passive".equals(provider)) {
        continue;
      }
      isAvailable = locationManager.isProviderEnabled(provider);
      if (isAvailable) {
        availableProviders++;
      }
      //if (mPluginLayout != null && mPluginLayout.isDebug) {
      //}
    }
    if (availableProviders == 0) {
      JSONObject result = new JSONObject();
      try {
        result.put("status", false);
        result.put("error_code", "not_available");
        result.put("error_message", PluginUtil.getPgmStrings(activity,"pgm_no_location_providers"));
      } catch (JSONException e) {
        e.printStackTrace();
      }
      callbackContext.error(result);
      return;
    }

    _inviteLocationUpdateAfterActivityResult(bundle);
  }

  private void _inviteLocationUpdateAfterActivityResult(Bundle bundle) {
    boolean enableHighAccuracy = bundle.getBoolean("enableHighAccuracy");
    String callbackId = bundle.getString("callbackId");
    CallbackContext callbackContext = new CallbackContext(callbackId, this.webView);
    this._requestLocationUpdate(false, enableHighAccuracy, callbackContext);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (!bufferForLocationDialog.containsKey("bundle_" + requestCode)) {
      return;
    }
    Bundle query = bufferForLocationDialog.get("bundle_" + requestCode);

    switch (query.getInt("type")) {
      case ACTIVITY_LOCATION_DIALOG:
        // User was asked to enable the location setting.
        switch (resultCode) {
          case Activity.RESULT_OK:
            // All required changes were successfully made
            _inviteLocationUpdateAfterActivityResult(query);
            break;
          case Activity.RESULT_CANCELED:
            // The user was asked to change settings, but chose not to
            _userRefusedToUseLocationAfterActivityResult(query);
            break;
          default:
            break;
        }
        break;
      case ACTIVITY_LOCATION_PAGE:
        _onActivityResultLocationPage(query);
        break;
    }
  }
  private void _userRefusedToUseLocationAfterActivityResult(Bundle bundle) {
    String callbackId = bundle.getString("callbackId");
    CallbackContext callbackContext = new CallbackContext(callbackId, this.webView);
    JSONObject result = new JSONObject();
    try {
      result.put("status", false);
      result.put("error_code", "service_denied");
      result.put("error_message", PluginUtil.getPgmStrings(activity,"pgm_location_rejected_by_user"));
    } catch (JSONException e) {
      e.printStackTrace();
    }
    callbackContext.error(result);
  }

  public void onRequestPermissionResult(int requestCode, String[] permissions,
                                        int[] grantResults) throws JSONException {
    synchronized (semaphore) {
      semaphore.notify();
    }
  }

}
