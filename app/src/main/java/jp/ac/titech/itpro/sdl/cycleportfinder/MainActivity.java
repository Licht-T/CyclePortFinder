package jp.ac.titech.itpro.sdl.cycleportfinder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.location.Location;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Xml;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.maps.android.MarkerManager;
import com.google.maps.android.kml.KmlContainer;
import com.google.maps.android.kml.KmlLayer;
import com.google.maps.android.kml.KmlPlacemark;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import org.apache.commons.validator.routines.UrlValidator;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;


import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import de.biomedical_imaging.edu.wlu.cs.levy.CG.KDTree;
import de.biomedical_imaging.edu.wlu.cs.levy.CG.KeyDuplicateException;
import de.biomedical_imaging.edu.wlu.cs.levy.CG.KeySizeException;

public class MainActivity extends AppCompatActivity implements
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener {
    private final static String TAG = "MainActivity";

    private GoogleMap googleMap;
    private GoogleApiClient googleApiClient;
    private LocationRequest locationRequest;
    private boolean requestingLocationUpdate;

    private KmlLayer layer;
    private MarkerManager markerManager;

    private KDTree<MarkerOptions> tree;
    private List<MarkerOptions> markerOptionsList;
    private HashMap<MarkerOptions, Marker> markerMap;
    private MarkerOptions opt_passed_by_intent;

    private Location currentLocation;

    private enum UpdatingState {STOPPED, REQUESTING, STARTED}

    private UpdatingState state = UpdatingState.STOPPED;

    private final static String[] PERMISSIONS = {
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };
    private final static int REQCODE_PERMISSIONS = 1111;

    public static double[] convertDouble(List<Double> doubles) {
        double[] ret = new double[doubles.size()];
        Iterator<Double> iterator = doubles.iterator();
        for (int i = 0; i < ret.length; i++) {
            ret[i] = iterator.next().doubleValue();
        }
        return ret;
    }

    private class InfoWindowRefresher implements Callback {
        private Marker markerToRefresh = null;

        private InfoWindowRefresher(Marker markerToRefresh) {
            this.markerToRefresh = markerToRefresh;
        }

        @Override
        public void onSuccess() {
            markerToRefresh.showInfoWindow();
        }

        @Override
        public void onError() {}
    }

    private class InfoWindowAdapter implements GoogleMap.InfoWindowAdapter {
        private final View contentsView;
        private boolean initialized = false;
        private List<String> url_read;

        InfoWindowAdapter(){
            contentsView = getLayoutInflater().inflate(R.layout.info_window, null);
            url_read = new ArrayList<>();
        }

        @Override
        public View getInfoWindow(Marker marker) {
            // inflate view window
            Log.d(TAG, marker.getTitle());
            Log.d(TAG, marker.getSnippet());

            // set other views content
            TextView title = ((TextView)contentsView.findViewById(R.id.title));
            title.setText(marker.getTitle());

            // set image view like this:
            String url = marker.getSnippet();
            UrlValidator validator = new UrlValidator();

            if (validator.isValid(url)) {
                if (url_read.indexOf(url) >= 0) {
                    Picasso.with(getApplicationContext()).load(url).into((ImageView) contentsView.findViewById(R.id.image));
                } else {
                    Picasso.with(getApplicationContext()).load(url).into((ImageView) contentsView.findViewById(R.id.image), new InfoWindowRefresher(marker));
                    url_read.add(url);
                }
            }

            return contentsView;
        }

        @Override
        public View getInfoContents(Marker marker) {
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MapFragment mapFragment = (MapFragment) getFragmentManager().findFragmentById(R.id.map_fragment);
        mapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap map) {
                map.moveCamera(CameraUpdateFactory.zoomTo(15f));
                googleMap = map;
            }
        });

        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        locationRequest = new LocationRequest();
        locationRequest.setInterval(10000);
        locationRequest.setFastestInterval(5000);
        locationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);

        currentLocation = null;

        InputStream is = getResources().openRawResource(R.raw.all);

        BufferedReader br = new BufferedReader(new InputStreamReader(is));

        StringBuilder sb = new StringBuilder();
        try {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        tree = new KDTree<>(2);
        markerOptionsList = new ArrayList<>();
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = dbFactory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(sb.toString().getBytes()));

            XPathFactory factory = XPathFactory.newInstance();
            XPath xpath = factory.newXPath();

            NodeList placemarks = (NodeList) xpath.evaluate("//Placemark", document, XPathConstants.NODESET);
            for(int i = 0; i < placemarks.getLength(); i++) {
                Node node = placemarks.item(i);
                String name = xpath.evaluate("./name/text()", node);
                String[] coords_str = xpath.evaluate(".//coordinates/text()", node).split(",", 0);
                String[] url_str = xpath.evaluate(".//value/text()", node).split(" ", 0);

                Double lat = Double.parseDouble(coords_str[1]);
                Double lng = Double.parseDouble(coords_str[0]);
                LatLng latlng = new LatLng(lat, lng);

                List<Double> l = new ArrayList<>();
                l.add(lat);
                l.add(lng);

                Log.d(TAG, name);
                Log.d(TAG, coords_str[0]+coords_str[1]);
                Log.d(TAG, url_str[0]);

                MarkerOptions markerOptions = new MarkerOptions();
                markerOptions.position(latlng);
                markerOptions.title(name);
                markerOptions.snippet(url_str[0]);

                tree.insert(this.convertDouble(l), markerOptions);
                markerOptionsList.add(markerOptions);
            }

        } catch (SAXException e) {
            e.printStackTrace();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (XPathExpressionException e) {
            e.printStackTrace();
        } catch (KeyDuplicateException e) {
            e.printStackTrace();
        } catch (KeySizeException e) {
            e.printStackTrace();
        }

        Log.d(TAG, "Created");

    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        googleApiClient.connect();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        if (state != UpdatingState.STARTED && googleApiClient.isConnected())
            startLocationUpdate(true);
        else
            state = UpdatingState.REQUESTING;
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        if (state == UpdatingState.STARTED)
            stopLocationUpdate();
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        googleApiClient.disconnect();
        super.onStop();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(TAG, "onConnected");
        if (state == UpdatingState.REQUESTING) {
            startLocationUpdate(true);
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        googleMap.setMyLocationEnabled(true);

        googleMap.setInfoWindowAdapter(new InfoWindowAdapter());

        markerMap = new HashMap<MarkerOptions, Marker>();
        for (MarkerOptions opt : markerOptionsList){
            Marker m = googleMap.addMarker(opt);
            markerMap.put(opt, m);
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG, "onConnectionSuspended");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.d(TAG, "onConnectionFailed");
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.d(TAG, "onLocationChanged: " + location);
        if (currentLocation == null){
            googleMap.animateCamera(CameraUpdateFactory
                   .newLatLng(new LatLng(location.getLatitude(), location.getLongitude())));
        }
        currentLocation = location;
    }

    @Override
    public void onRequestPermissionsResult(int reqCode,
                                           @NonNull String[] permissions, @NonNull int[] grants) {
        Log.d(TAG, "onRequestPermissionsResult");
        switch (reqCode) {
        case REQCODE_PERMISSIONS:
            startLocationUpdate(false);
            break;
        }
    }

    private void startLocationUpdate(boolean reqPermission) {
        Log.d(TAG, "startLocationUpdate: " + reqPermission);
        for (String permission : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                if (reqPermission)
                    ActivityCompat.requestPermissions(this, PERMISSIONS, REQCODE_PERMISSIONS);
                else
                    Toast.makeText(this, getString(R.string.toast_requires_permission, permission),
                            Toast.LENGTH_SHORT).show();
                return;
            }
        }
        LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this);
        state = UpdatingState.STARTED;
    }

    private void stopLocationUpdate() {
        Log.d(TAG, "stopLocationUpdate");
        LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this);
        state = UpdatingState.STOPPED;
    }

    public void onClickCurrentLoc(View v) {
        googleMap.animateCamera(CameraUpdateFactory
               .newLatLng(new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude())));
    }
}
