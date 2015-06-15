package com.github.jtjj222.sudburytransit.fragments;

import android.animation.TimeInterpolator;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.github.jtjj222.sudburytransit.R;
import com.github.jtjj222.sudburytransit.maps.BusStopOverlay;
import com.github.jtjj222.sudburytransit.maps.BusStopOverlayItem;
import com.github.jtjj222.sudburytransit.maps.RouteOverlay;
import com.github.jtjj222.sudburytransit.models.MyBus;
import com.github.jtjj222.sudburytransit.models.Pelias;
import com.github.jtjj222.sudburytransit.models.Place;
import com.github.jtjj222.sudburytransit.models.Route;
import com.github.jtjj222.sudburytransit.models.SimpleDiskCache;
import com.github.jtjj222.sudburytransit.models.Stop;
import com.sothree.slidinguppanel.SlidingUpPanelLayout;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;


public class StopsMapFragment extends Fragment {

    public BusStopOverlay busStopOverlay;
    public RouteOverlay routeOverlay;
    public MyLocationNewOverlay myLocationOverlay;

    private ArrayList<Route> routes = new ArrayList<>();
    private ArrayList<Stop> stops = new ArrayList<>();

    private ArrayList<String> placeLocations = new ArrayList<>();

    private SimpleDiskCache cache;

    public MapView map;

    private LinearLayout searchDrawer = null;
    private boolean searchDrawerOpened = false;
    private TimeInterpolator interpolator = null; //type of animation see@developer.android.com/reference/android/animation/TimeInterpolator.html

    public boolean stopsLoaded = false, routesLoaded = false;

    private View view = null;

    @Override
    public View onCreateView(LayoutInflater inflater, final ViewGroup parent, Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = inflater.inflate(R.layout.fragment_stops_map, parent, false);

        searchDrawer = (LinearLayout) view.findViewById(R.id.searchDrawer);
        interpolator = new AccelerateDecelerateInterpolator();

        searchDrawer.post(new Runnable() {
            @Override
            public void run() {
                searchDrawer.setTranslationY(-searchDrawer.getHeight());
            }
        });

        setHasOptionsMenu(true);

        map = (MapView) view.findViewById(R.id.map);
        //TODO replace with our own tiles
        map.setTileSource(TileSourceFactory.MAPNIK);

        map.setBuiltInZoomControls(false);
        map.setMultiTouchControls(true);
        map.getController().setZoom(20);
        map.getController().setCenter(new GeoPoint(46.491271667182488, -80.988006619736623));

        routeOverlay = new RouteOverlay(parent.getContext());
        map.getOverlays().add(routeOverlay);

        busStopOverlay = new BusStopOverlay(this, parent.getContext());
        map.getOverlays().add(busStopOverlay);

        view.findViewById(R.id.tglStops).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (((ToggleButton) view).isChecked()) busStopOverlay.setEnabled(true);
                else busStopOverlay.setEnabled(false);
                map.invalidate();
            }
        });

        final AutoCompleteTextView fromText = (AutoCompleteTextView) view.findViewById(R.id.fromEditText);

        fromText.addTextChangedListener(new TextWatcher() {

            public void afterTextChanged(Editable s) {
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                getLocationFromAddress(fromText.getText().toString(), view);
                ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(
                        parent.getContext(),
                        android.R.layout.simple_list_item_1,
                        placeLocations);
            }
        });

        view.findViewById(R.id.btnViewDirections).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //TODO Alec replace this with results from search fields
                navigate(new GeoPoint(46.4886476,-80.9351185), new GeoPoint(46.4692213,-81.0247679));
            }
        });

        // When we get the location or we get the list of stops (whichever comes last)
        // we move the map to their location (if they haven't already selected a stop)
        myLocationOverlay = new MyLocationNewOverlay(parent.getContext(), map);
        myLocationOverlay.enableMyLocation();
        myLocationOverlay.disableFollowLocation();
        myLocationOverlay.setDrawAccuracyEnabled(true);
        myLocationOverlay.runOnFirstFix(new Runnable() {
            public void run() {
                map.getController().animateTo(myLocationOverlay
                        .getMyLocation());
                focusClosestStop(myLocationOverlay.getMyLocation());
            }
        });
        map.getOverlays().add(myLocationOverlay);

        loadData(parent);

        ((SlidingUpPanelLayout) view).setPanelSlideListener(new SlidingUpPanelLayout.PanelSlideListener() {
            double lastOffset = 1.0;

            @Override
            public void onPanelSlide(View panel, float slideOffset) {
                if (slideOffset < lastOffset) {
                    ((SlidingUpPanelLayout) view).setPanelHeight(0);
                    ((SlidingUpPanelLayout) view).setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
                    busStopOverlay.setFocus(null);
                }
                lastOffset = slideOffset;
            }

            @Override
            public void onPanelExpanded(View panel) {
            }

            @Override
            public void onPanelCollapsed(View panel) {
            }

            @Override
            public void onPanelAnchored(View panel) {
            }

            @Override
            public void onPanelHidden(View panel) {
            }
        });

        return view;
    }

    public void getLocationFromAddress(String search, final View parent) {
        Pelias.getSuggestedLocations(search, new Callback<ArrayList<Place>>() {
            @Override
            public void success(ArrayList<Place> places, Response response) {
                for (Place p : places) placeLocations.add(p.houseNumber + " " + p.street + ", " + p.city + ", " + p.state + ", " + p.country);
            }

            @Override
            public void failure(RetrofitError error) {
                Pelias.onFailure(parent.getContext(), error);
            }
        });
    }

    @Override
    public void onStop() {
        super.onStop();
        System.out.println("On stop");
        try {
            cache.closeAllDirs();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadData(final View parent) {
        try {
            cache = SimpleDiskCache.open(parent.getContext().getCacheDir(), 1, 1048576);
        } catch (IOException e) {
            e.printStackTrace();
        }

        MyBus.loadStops(parent.getContext(), cache, new Callback<ArrayList<Stop>>() {
            @Override
            public void success(ArrayList<Stop> stops, Response response) {
                StopsMapFragment.this.stops = stops;
                for (Stop s : stops) busStopOverlay.addItem(new BusStopOverlayItem(s));
                focusClosestStop(myLocationOverlay.getMyLocation());
                map.invalidate();

                stopsLoaded = true;
                onDataLoaded();
            }

            @Override
            public void failure(RetrofitError error) {
                MyBus.onFailure(parent.getContext(), error);
            }
        });

        MyBus.loadRoutes(parent.getContext(), cache, new Callback<ArrayList<Route>>() {
            @Override
            public void success(ArrayList<Route> routes, Response response) {
                StopsMapFragment.this.routes = routes;

                routesLoaded = true;
                onDataLoaded();
            }

            @Override
            public void failure(RetrofitError error) {
                MyBus.onFailure(parent.getContext(), error);
            }
        });
    }

    private void onDataLoaded() {
        if (routesLoaded && stopsLoaded) {
            view.findViewById(R.id.loading).setVisibility(View.GONE);
            System.out.println("Data loaded!");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_search:
                openSearch();
                return true;
            case R.id.action_settings:
                System.out.println("Settings test");
                // openSettings();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void openSearch() {
        int searchDrawerDuration = 500;

        if(searchDrawerOpened) {
            searchDrawer.animate()
                    .translationY(-searchDrawer.getHeight())
                    .setDuration(searchDrawerDuration)
                    .setInterpolator(interpolator)
                    .start();
            searchDrawerOpened = false;
        } else {
            searchDrawer.animate()
                    .translationY(0)
                    .setDuration(searchDrawerDuration)
                    .setInterpolator(interpolator)
                    .start();
            searchDrawerOpened = true;
        }
    }

    private void focusClosestStop(GeoPoint location) {
        if (location != null && busStopOverlay.getFocus() == null) {

            BusStopOverlayItem closest = null;
            for (int i = 0; i < busStopOverlay.size(); i++) {
                BusStopOverlayItem item = busStopOverlay.getItem(i);

                if (closest == null
                        || new GeoPoint(closest.getStop().latitude,
                            closest.getStop().longitude).distanceTo(location)
                        > new GeoPoint(item.getStop().latitude, item.getStop().longitude)
                            .distanceTo(location)) {
                    closest = item;
                }
            }

            busStopOverlay.setFocus(closest);
        }
    }

    // We do k shortest path graph search on a digraph,
    // where each available route forms an edge from one stop to another
    // This is done so that it can recommend transfers at places other
    // than the transit terminal

    // When we get timing data from the city, timing will be used in the weights
    // The graph also contains nodes for the from and to locations, with
    // the distance being the weight

    protected static class RouteEdge {

        public String route;
        public Stop a, b;

        public RouteEdge(Stop a, Stop b, String route) {
            this.a = a;
            this.b = b;
            this.route = route;
        }

    }

    protected static class RouteGraph {
        private HashMap<Integer, ArrayList<RouteEdge>> adjacents = new HashMap<>();

        public void addEdge(RouteEdge e) {
            if (isAdjacent(e.a, e.b)) return;
            if (adjacents.get(e.a.number) == null) adjacents.put(e.a.number, new ArrayList<RouteEdge>());
            adjacents.get(e.a.number).add(e);
        }

        public boolean isAdjacent(Stop a, Stop b) {
            if (adjacents.get(a.number) == null) return false;

            for (RouteEdge e : adj(a.number)) {
                if (e.b.number == b.number) return true;
            }
            return false;
        }

        public Collection<RouteEdge> adj(int stop) {
            if (!adjacents.containsKey(stop)) return new ArrayList<>();
            return adjacents.get(stop);
        }

        public Set<Integer> vertices() {
            return adjacents.keySet();
        }
    }

    private RouteGraph buildRouteGraph() {
        RouteGraph graph = new RouteGraph();

        //Until we can get order data from the city,
        //this directed graph will be wrong
        for (Route route : routes) {

            if (route.stops.size() == 0) continue;

            Stop last = null;
            for (Stop stop : route.stops) {
                if (last == null) {
                    last = stop;
                    continue;
                }

                graph.addEdge(new RouteEdge(last, stop, route.number));
                last = stop;
            }

            graph.addEdge(new RouteEdge(last, route.stops.get(0), route.number));
        }

        return graph;
    }

    //Copied from GeopPoint
    private static float dist(double latA, double longA, double latB, double longB) {
        final double a1 = GeoPoint.DEG2RAD * latA;
        final double a2 = GeoPoint.DEG2RAD * longA;
        final double b1 = GeoPoint.DEG2RAD * latB;
        final double b2 = GeoPoint.DEG2RAD * longB;

        final double cosa1 = Math.cos(a1);
        final double cosb1 = Math.cos(b1);

        final double t1 = cosa1 * Math.cos(a2) * cosb1 * Math.cos(b2);

        final double t2 = cosa1 * Math.sin(a2) * cosb1 * Math.sin(b2);

        final double t3 = Math.sin(a1) * Math.sin(b1);

        final double tt = Math.acos(t1 + t2 + t3);

        return (int) (GeoPoint.RADIUS_EARTH_METERS * tt);
    }

    private float getPathCost(RouteEdge[] a) {
        int transfersTime = 0;
        float distanceTime = 0;

        for (int i=0; i<a.length; i++) {
            if (a[i].route.equals("Walking")) {
                //Distance in m
                float dist = dist(a[i].a.latitude, a[i].a.longitude, a[i].b.latitude, a[i].b.longitude);
                distanceTime += dist/(5*1000/3600); // (m) / (5km/h * 1000m/km / 3600s/h)
            }
            else distanceTime += 1; //TODO replace with bus schedule times

            if (i != 0 && !a[i-1].route.equals(a[i].route))  transfersTime += 5*60; //Assume 5 min. for transfer
        }

        return transfersTime + distanceTime;
    }

    //K shortest path algorithm
    private void navigate(GeoPoint fromPoint, GeoPoint toPoint) {

        int K = 5; //TODO load from preference; Max number of routes to find

        //TODO refine the weighting to allow specifying whether or not you are willing to walk
        //TODO fix this method returning multiple redundant routes with the same bus
        PriorityQueue<RouteEdge[]> routes = new PriorityQueue<>(10, new Comparator<RouteEdge[]>() {

            @Override
            public int compare(RouteEdge[] a, RouteEdge[] b) {
                return (int) Math.signum(getPathCost(a) - getPathCost(b));
            }
        });

        HashMap<Integer, Integer> seen = new HashMap<>();
        LinkedList<RouteEdge[]> pathsFound = new LinkedList<>();
        RouteGraph graph = buildRouteGraph();

        //Add source and sink vertices
        Stop from = getStop(-2);
        if (from == null) {
            from = new Stop();
            stops.add(from);
            busStopOverlay.addItem(new BusStopOverlayItem(from));
        }
        from.latitude = fromPoint.getLatitude();
        from.longitude = fromPoint.getLongitude();
        from.name = "From";
        from.number = -2;

        Stop to = getStop(-1);
        if (to == null) {
            to = new Stop();
            stops.add(to);
            busStopOverlay.addItem(new BusStopOverlayItem(to));
        }
        to.latitude = toPoint.getLatitude();
        to.longitude = toPoint.getLongitude();
        to.name = "To";
        to.number = -1;

        // Connect source and sink to every stop
        for (Stop s : stops) {
            if (s.number == from.number || s.number == to.number) continue;
            graph.addEdge(new RouteEdge(from, s, "Walking"));
            graph.addEdge(new RouteEdge(s, to, "Walking"));
        }
        // Connect source and sink together, so that if it makes sense
        // to walk instead of taking the bus, we offer that option too
        graph.addEdge(new RouteEdge(from, to, "Walking"));

        // Set each stop to being never being seen
        for (Stop s : stops) {seen.put(s.number, 0);}

        //Visit the source
        for (RouteEdge e : graph.adj(from.number)) {
            routes.add(new RouteEdge[]{e});
        }
        seen.put(from.number, 1);

        //Visit every stop at most K times, storing the route used to get there
        while (!routes.isEmpty() && seen.get(to.number) < K) {
            RouteEdge[] route = routes.remove();

            int b = route[route.length-1].b.number;
            if (seen.get(b) >= K) continue;
            seen.put(b, seen.get(b) + 1);

            if (b == to.number) {
                pathsFound.add(route);
                continue;
            }

            for (RouteEdge e : graph.adj(b)) {
                routes.add(push_copy(e, route));
            }
        }

        showNavigationResult(pathsFound);
    }

    private void showNavigationResult(LinkedList<RouteEdge[]> pathsFound) {

        StringBuilder directions = new StringBuilder();

        directions.append(pathsFound.size()).append(" paths found:");
        for (RouteEdge[] path : pathsFound) {
            directions.append("\nPath: ").append(getPathCost(path) / 60f).append(" min\n");

            for (int i=0; i<path.length; i++) {
                if (i != 0 && path[i].route.equals(path[i-1].route)) continue;

                if (path[i].route.equals("Walking")) {
                    directions.append("- Walk from stop ").append(path[i].a.number)
                        .append(" ").append(path[i].a.name).append(" to stop ")
                        .append(path[i].b.number).append(" ").append(path[i].b.name)
                        .append("\n");
                }
                else {
                    if (i > 0 && !path[i-1].route.equals("Walking")) {
                        directions.append("- Get off bus at ").append(path[i].a.number)
                                .append(" ").append(path[i].a.name)
                                .append("\n- Get on bus ").append(path[i].route)
                                .append(" at this stop.\n");
                    }
                    else {
                        directions.append("- Get on bus ").append(path[i].route)
                                .append(" at stop ").append(path[i].a.number)
                                .append(" ").append(path[i].a.name)
                                .append(".\n");
                    }
                }
            }

            //debugging
//            for (RouteEdge e : path) {
//                System.out.println("Go from " + e.a.number + " " + e.a.name + " to "
//                    + e.b.number + " " + e.b.name + " via " + e.route);
//            }
        }

        ((TextView) view.findViewById(R.id.txtDirections)).setText(directions.toString());

        visualizePaths(pathsFound);
    }

    //Debug method
    private void visualizeRouteGraph(RouteGraph graph) {
        routeOverlay.routes.clear();

        for (int stop : graph.vertices()) {
            for (RouteEdge e : graph.adj(stop)) {
                Route r = new Route();
                r.stops = new ArrayList<>();
                r.stops.add(e.a);
                r.stops.add(e.b);
                routeOverlay.routes.add(r);
            }
        }
    }

    //Debug method
    private void visualizePaths(List<RouteEdge[]> paths) {
        routeOverlay.routes.clear();

        for (RouteEdge[] path : paths) {

            Route r = new Route();
            r.stops = new ArrayList<>();

            for (int i=0; i<path.length; i++) {
                if (i == 0) r.stops.add(path[i].a);
                else r.stops.add(path[i].b);
            }

            routeOverlay.routes.add(r);
        }
    }

    private boolean containsStop(int stop, RouteEdge... haystack) {
        for (RouteEdge e : haystack) {
            if (e.a.number == stop || e.b.number==stop) return true;
        }
        return false;
    }

    private <T> T[] push_copy(T tl, T[] list) {
        T[] n = Arrays.copyOf(list, list.length+1);
        n[n.length-1] = tl;
        return n;
    }

    private Stop getStop(int number) {
        for (Stop s : stops) {
            if (s.number == number) return s;
        }
        return null;
    }

    private Route getRoute(String number) {
        for (Route r : routes) {
            if (r.number.equals(number)) return r;
        }
        return null;
    }
}
