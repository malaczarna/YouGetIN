package pl.voozer.ui.main

import android.os.Bundle
import com.tbruyelle.rxpermissions2.RxPermissions
import pl.voozer.R
import pl.voozer.ui.base.BaseActivity
import android.Manifest.permission
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.model.TypeFilter
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.AutocompleteActivity
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.android.material.navigation.NavigationView
import com.google.firebase.iid.FirebaseInstanceId
import com.google.maps.android.PolyUtil
import com.sothree.slidinguppanel.SlidingUpPanelLayout
import kotlinx.android.synthetic.main.content_main.*
import pl.voozer.BuildConfig
import pl.voozer.service.bubble.FloatingService
import pl.voozer.service.model.*
import pl.voozer.service.model.direction.Direction
import pl.voozer.service.network.Connection
import pl.voozer.ui.adapter.DriversAdapter
import pl.voozer.ui.login.LoginActivity
import pl.voozer.utils.*
import java.util.*
import kotlin.collections.ArrayList

class MainActivity : BaseActivity<MainController, MainView>(), MainView, OnMapReadyCallback,
    GoogleMap.OnMarkerClickListener,
    NavigationView.OnNavigationItemSelectedListener {

    private lateinit var googleMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var lastLocation: Location
    private lateinit var destination: Destination
    private lateinit var place: Place
    private var routePoints: List<LatLng> = emptyList()
    private lateinit var user: User
    private lateinit var toolbar: Toolbar
    private lateinit var navView: NavigationView
    private lateinit var oppositeUser: User
    private lateinit var driverId: String
    private lateinit var passengerId: String
    private var requestingLocationUpdates: Boolean = false
    private var isDestinationReloadNeeded = false
    private var fields = Arrays.asList(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
    private var lastRoute: Polyline? = null
    private var notificationReceiver: BroadcastReceiver? = null
    private var waitingDialog: MaterialDialog? = null
    private var meetingLat: Double? = null
    private var meetingLng: Double? = null
    private val AUTOCOMPLETE_REQUEST_CODE = 1


    override fun onMapReady(googleMap: GoogleMap) {
        this.googleMap = googleMap
        googleMap.uiSettings.isMyLocationButtonEnabled = false
        googleMap.setOnMarkerClickListener(this)
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(
                applicationContext,
                permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            googleMap.isMyLocationEnabled = true
            fusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
                if (location != null) {
                    lastLocation = location
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 12f))
                }
            }
        }
    }

    override fun onMarkerClick(marker: Marker): Boolean = false

    override fun updatePosition(position: Position) {
        Log.d("latLng", "${position.lat}, ${position.lng}")
    }

    override fun updateDrivers(drivers: List<User>) {
        hideProgressDialog()
        if (drivers.isEmpty()) {
            tvNoDrivers.visibility = View.VISIBLE
        } else {
            tvNoDrivers.visibility = View.GONE
        }
        for (driver in drivers) {
            placeDriverMarkerOnMap(LatLng(driver.lat, driver.lng))
        }
        rvDriversList.layoutManager = LinearLayoutManager(applicationContext)
        rvDriversList.adapter = DriversAdapter(drivers = drivers, context = applicationContext, listener = object: DriversAdapter.OnItemClickListener {
            override fun onDriverClick(driver: User) {
                controller.sendNotification(NotificationMessage(passengerId = user.id, driverId = driver.id, notificationType = NotificationType.ASK))
                splMain.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
                waitingDialog = MaterialDialog(this@MainActivity).show {
                    title(text = "Oczekiwanie na odpowiedz kierowcy...")
                    cancelOnTouchOutside(false)
                    noAutoDismiss()
                    negativeButton(text = "Anuluj") {
                        dismiss()
                        controller.sendNotification(
                            NotificationMessage(
                                passengerId = user.id,
                                driverId = driver.id,
                                notificationType = NotificationType.DECLINE
                            )
                        )
                    }
                }
            }
        })
    }

    override fun updateFirebaseToken() {
        SharedPreferencesHelper.setFirebaseInit(applicationContext, true)
    }

    override fun updateUser(user: User) {
        this.user = user
        tvUsername.text = "${getString(R.string.search_bar_greeting)} ${user.name}!"
        when (user.profile) {
            Profile.PASSENGER -> {
                navView.getHeaderView(0).findViewById<LinearLayout>(R.id.llHeaderMain).setBackgroundColor(
                    ContextCompat.getColor(applicationContext, R.color.colorPrimary
                    ))
                fabMyPosition.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(applicationContext, R.color.colorPrimary))
                toolbar.findViewById<ImageView>(R.id.ivProfile)
                    .setImageDrawable(ContextCompat.getDrawable(applicationContext, R.drawable.ic_walking))
                toolbar.findViewById<TextView>(R.id.tvProfile).text = getString(R.string.menu_passenger)
                btnAcceptDestination.setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.colorPrimary))
            }
            Profile.DRIVER -> {
                navView.getHeaderView(0).findViewById<LinearLayout>(R.id.llHeaderMain).setBackgroundColor(
                    ContextCompat.getColor(applicationContext, R.color.colorPrimaryDriver
                    ))
                fabMyPosition.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(applicationContext, R.color.colorPrimaryDriver))
                toolbar.findViewById<ImageView>(R.id.ivProfile)
                    .setImageDrawable(ContextCompat.getDrawable(applicationContext, R.drawable.ic_steering_wheel))
                toolbar.findViewById<TextView>(R.id.tvProfile).text = getString(R.string.menu_driver)
                btnAcceptDestination.setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.colorPrimaryDriver))
            }
        }
        when (SharedPreferencesHelper.isTripActive(applicationContext)) {
            true -> {
                val oppositeUser = SharedPreferencesHelper.getData<User>(context = applicationContext, key = "opposite-user-data")
                tvSplTitle.text = "Informacje"
                tvPersonPhone.text = HtmlCompat.fromHtml("<b>Numer telefonu: </b>${oppositeUser?.phoneNumber}", HtmlCompat.FROM_HTML_MODE_LEGACY)
                tvInformationMeetingPoint.text = HtmlCompat.fromHtml("<b>Punkt spotkania: </b>ul. ${SharedPreferencesHelper.getMeetingName(applicationContext)}", HtmlCompat.FROM_HTML_MODE_LEGACY)
                when (user.profile) {
                    Profile.DRIVER -> {
                        llDrivers.visibility = View.GONE
                        llInformation.visibility = View.VISIBLE
                        btnFinishRide.text = "Pasażer wysiadł z samochodu."
                        btnCancelRide.text = "Pasażer nie przyjechał na miejsce spotkania!"
                        splMain.anchorPoint = 0.5f
                        ObjectAnimator.ofFloat(llFabButtons, "translationY", -splMain.panelHeight.toFloat()).apply {
                            duration = 300
                            start()
                        }
                        Handler().postDelayed(
                            {
                                splMain.panelState = SlidingUpPanelLayout.PanelState.ANCHORED
                            },300L
                        )
                    }
                    Profile.PASSENGER -> {
                        llDrivers.visibility = View.GONE
                        llInformation.visibility = View.VISIBLE
                        tvCarBrand.text = HtmlCompat.fromHtml("<b>Marka: </b> ${oppositeUser?.carBrand}", HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvCarModel.text = HtmlCompat.fromHtml("<b>Model: </b> ${oppositeUser?.carModel}", HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvCarColor.text = HtmlCompat.fromHtml("<b>Kolor: </b> ${oppositeUser?.carColor}", HtmlCompat.FROM_HTML_MODE_LEGACY)
                        llInformationDriver.visibility = View.VISIBLE
                        btnFinishRide.text = "Dojechałem na miejsce."
                        btnCancelRide.text = "Kierowca nie przyjechał na miejsce spotkania!"
                        splMain.anchorPoint = 0.5f
                        ObjectAnimator.ofFloat(llFabButtons, "translationY", -splMain.panelHeight.toFloat()).apply {
                            duration = 300
                            start()
                        }
                        Handler().postDelayed(
                            {
                                splMain.panelState = SlidingUpPanelLayout.PanelState.ANCHORED
                            },300L
                        )
                    }
                }
            }
        }
    }

    override fun updateOppositeUser(user: User) {
        hideProgressDialog()
        oppositeUser = user
        SharedPreferencesHelper.setTripActive(context = applicationContext, value = true)
        SharedPreferencesHelper.setData(context = applicationContext, key = "opposite-user-data", data = oppositeUser)
        requestingLocationUpdates = true
        startLocationUpdates()
        llDrivers.visibility = View.GONE
        llPassengers.visibility = View.GONE
        tvSplTitle.text = "Informacje"
        tvPersonPhone.text = HtmlCompat.fromHtml("<b>Numer telefonu: </b>${oppositeUser.phoneNumber}", HtmlCompat.FROM_HTML_MODE_LEGACY)
        tvInformationMeetingPoint.text = HtmlCompat.fromHtml("<b>Punkt spotkania: </b>ul. ${SharedPreferencesHelper.getMeetingName(applicationContext)}", HtmlCompat.FROM_HTML_MODE_LEGACY)
        when (this.user.profile) {
            Profile.PASSENGER -> {
                waitingDialog?.dismiss()
                tvCarBrand.text = HtmlCompat.fromHtml("<b>Marka: </b> ${oppositeUser.carBrand}", HtmlCompat.FROM_HTML_MODE_LEGACY)
                tvCarModel.text = HtmlCompat.fromHtml("<b>Model: </b> ${oppositeUser.carModel}", HtmlCompat.FROM_HTML_MODE_LEGACY)
                tvCarColor.text = HtmlCompat.fromHtml("<b>Kolor: </b> ${oppositeUser.carColor}", HtmlCompat.FROM_HTML_MODE_LEGACY)
                llInformationDriver.visibility = View.VISIBLE
                Toast.makeText(applicationContext, "Kierowca zaakceptował przejażdżkę!", Toast.LENGTH_LONG).show()
                btnFinishRide.text = "Dojechałem na miejsce."
                btnCancelRide.text = "Kierowca nie przyjechał na miejsce spotkania!"
            }
            Profile.DRIVER -> {
                btnFinishRide.text = "Pasażer wysiadł z samochodu."
                btnCancelRide.text = "Pasażer nie przyjechał na miejsce spotkania!"
                val navigationIntentUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + user.destination.lat +"," + user.destination.lng + "&waypoints=" +  SharedPreferencesHelper.getMeetingLat(applicationContext) +"," + SharedPreferencesHelper.getMeetingLng(applicationContext) + "&travelmode=driving")
                val mapIntent = Intent(Intent.ACTION_VIEW, navigationIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                startActivity(mapIntent)
            }
        }
        llInformation.visibility = View.VISIBLE
        splMain.panelState = SlidingUpPanelLayout.PanelState.ANCHORED
    }

    override fun setRoute(direction: Direction) {
        hideProgressDialog()
        lastRoute?.remove()
        val options = PolylineOptions()
        options.color(ContextCompat.getColor(applicationContext, R.color.colorPrimaryDriver))
        options.width(5f)

        val points = direction.routes[0].legs[0].steps
        routePoints = points.flatMap { PolyUtil.decode(it.polyline.points) }
        val arrayPoints = points.map { PolyUtil.decode(it.polyline.points) }
        var pointsCount = ArrayList<Int>()
        var time = 0.0
        for (point in arrayPoints) {
            pointsCount.add(point.count())
        }
        for (point in routePoints) {
            options.add(point)
        }
        var helpIndex = 0
        var totalPointsCount = pointsCount[0]
        val routePointsTime: ArrayList<PositionWithTime> = ArrayList()
        routePoints.forEachIndexed { index, steps ->
            if (index > totalPointsCount) {
                helpIndex++
                totalPointsCount = pointsCount[helpIndex] + totalPointsCount
            }
            time = (points[helpIndex].duration.value / pointsCount[helpIndex]) + time
            routePointsTime.add(
                PositionWithTime(
                    lat = steps.latitude,
                    lng = steps.longitude,
                    seconds = time
                )
            )
        }
        lastRoute = googleMap.addPolyline(options)
        destination = Destination(
            name = place.name!!,
            lat = place.latLng!!.latitude,
            lng = place.latLng!!.longitude,
            route = routePointsTime
        )
        user.destination = destination
        controller.setDestination(destination = destination)
        when (user.profile) {
            Profile.PASSENGER -> {
                showProgressDialog()
                controller.loadDrivers()
            }
            Profile.DRIVER -> {
                return
            }
        }
    }

    override fun getLayoutResId(): Int {
        return R.layout.activity_main
    }

    override fun initController() {
        controller = MainController()
        controller.view = this
        controller.api = Connection.Builder().provideOkHttpClient(applicationContext).provideRetrofit().createApi()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_rides -> {

            }
            R.id.nav_preferences -> {

            }
            R.id.nav_help -> {

            }
            R.id.nav_settings -> {

            }
            R.id.nav_logout -> {
                SharedPreferencesHelper.setFirebaseInit(applicationContext, false) //TODO: Send request for deleting firebase-token from database.
                SharedPreferencesHelper.setLoggedIn(applicationContext, false)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            R.id.nav_regulations -> {

            }
        }
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (data is Intent) {
            if (requestCode == AUTOCOMPLETE_REQUEST_CODE) {
                when (resultCode) {
                    RESULT_OK -> {
                        place = Autocomplete.getPlaceFromIntent(data)
                        place.latLng?.let {
                            SharedPreferencesHelper.setDestinationLat(applicationContext, it.latitude.toString())
                            SharedPreferencesHelper.setDestinationLng(applicationContext, it.longitude.toString())
                            placeDestinationMarkerOnMap(it)
                        }
                        tvSearch.text = place.address
                        if (ContextCompat.checkSelfPermission(
                                applicationContext,
                                permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            || ContextCompat.checkSelfPermission(
                                applicationContext,
                                permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            fusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
                                if (location != null) {
                                    lastLocation = location
                                    showProgressDialog()
                                    when(user.profile) {
                                        Profile.DRIVER -> {
                                            controller.loadDirection(
                                                api = Connection.Builder().provideOkHttpClient(applicationContext).provideRetrofit(url = DIRECTIONS_URL).createApi(),
                                                origin = "${lastLocation.latitude},${lastLocation.longitude}",
                                                destination = "${place.latLng!!.latitude},${place.latLng!!.longitude}",
                                                travelMode = TRAVEL_MODE_DRIVING,
                                                key = BuildConfig.GOOGLE_API_KEY
                                            )
                                        }
                                        Profile.PASSENGER -> {
                                            controller.loadDirection(
                                                api = Connection.Builder().provideOkHttpClient(applicationContext).provideRetrofit(url = DIRECTIONS_URL).createApi(),
                                                origin = "${lastLocation.latitude},${lastLocation.longitude}",
                                                destination = "${place.latLng!!.latitude},${place.latLng!!.longitude}",
                                                travelMode = TRAVEL_MODE_WALKING,
                                                key = BuildConfig.GOOGLE_API_KEY
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        when (user.profile) {
                            Profile.PASSENGER -> {
                                showProgressDialog()
                                splMain.anchorPoint = 0.7f
                                splMain.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
                                ObjectAnimator.ofFloat(llFabButtons, "translationY", -splMain.panelHeight.toFloat()).apply {
                                    duration = 300
                                    start()
                                }
                                Handler().postDelayed(
                                    {
                                        splMain.panelState = SlidingUpPanelLayout.PanelState.ANCHORED
                                    },300L
                                )
                            }
                            Profile.DRIVER -> {
                                btnAcceptDestination.isEnabled = true
                                ObjectAnimator.ofFloat(llAcceptDestination, "translationY", llHeaderBar.height.toFloat()).apply {
                                    duration = 1000
                                    start()
                                }
                            }
                        }
                    }
                    AutocompleteActivity.RESULT_ERROR -> {
                        val status = Autocomplete.getStatusFromIntent(data)
                        Log.i("Places-API", status.statusMessage)
                    }
                    RESULT_CANCELED -> {
                        // The user canceled the operation.
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationReceiver?.let { registerReceiver(it, IntentFilter(NOTIFICATION_BROADCAST_RECEIVER_ACTION)) }
        if (requestingLocationUpdates) {
            startLocationUpdates()
        }
        intent?.let { newIntent ->
            if(newIntent.action == SHOW_MESSAGE_ACTION) {
                newIntent.extras?.let { bundle ->
                    val driverId = bundle.getString("driverId", "0")
                    val passengerId = bundle.getString("passengerId", "0")
                    val notificationType = bundle.getString("type", "0")
                    val personName = bundle.getString("name", "0")
                    val meetingName = bundle.getString("meetingName", "0")
                    this@MainActivity.passengerId = passengerId
                    this@MainActivity.driverId = driverId
                    this@MainActivity.meetingLat = bundle.getString("meetingLat", "0.0").toDouble()
                    this@MainActivity.meetingLng = bundle.getString("meetingLng", "0.0").toDouble()
                    SharedPreferencesHelper.setMeetingName(applicationContext, bundle.getString("meetingName", "0"))
                    SharedPreferencesHelper.setMeetingLat(applicationContext, bundle.getString("meetingLat", "0.0"))
                    SharedPreferencesHelper.setMeetingLng(applicationContext, bundle.getString("meetingLng", "0.0"))
                    SharedPreferencesHelper.setDriverId(applicationContext, bundle.getString("driverId", "0"))
                    SharedPreferencesHelper.setPassengerId(applicationContext, bundle.getString("passengerId", "0"))
                    when (notificationType) {
                        NotificationType.ASK.name -> {
                            tvSplTitle.text = "Użytkownik $personName prosi o podwóżkę."
                            tvPassengerMeetingPoint.text = HtmlCompat.fromHtml("<b>Punkt spotkania: </b>ul. $meetingName", HtmlCompat.FROM_HTML_MODE_LEGACY)
                            llDrivers.visibility = View.GONE
                            llPassengers.visibility = View.VISIBLE
                            splMain.anchorPoint = 0.6f
                            ObjectAnimator.ofFloat(llFabButtons, "translationY", -splMain.panelHeight.toFloat()).apply {
                                duration = 300
                                start()
                            }
                            Handler().postDelayed(
                                {
                                    splMain.panelState = SlidingUpPanelLayout.PanelState.ANCHORED
                                },300L
                            )
                        }
                        NotificationType.ACCEPT.name -> {
                            showProgressDialog()
                            splMain.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
                            controller.loadSpecificUser(SharedPreferencesHelper.getDriverId(applicationContext)!!)
                        }
                        NotificationType.DECLINE.name -> {
                            when (user.profile) {
                                Profile.DRIVER -> {
                                    Toast.makeText(this, "Pasażer odrzucił prośbę!", Toast.LENGTH_LONG).show()
                                }
                                Profile.PASSENGER -> {
                                    Toast.makeText(this, "Kierwca anulował przjazd!", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    intent = null
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        notificationReceiver?.let { unregisterReceiver(it) }
    }

    override fun onBackPressed() {
        when {
            splMain.panelState == SlidingUpPanelLayout.PanelState.EXPANDED -> {
                splMain.panelState = SlidingUpPanelLayout.PanelState.ANCHORED
            }
            splMain.panelState == SlidingUpPanelLayout.PanelState.ANCHORED -> {
                splMain.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
            }
            splMain.panelState == SlidingUpPanelLayout.PanelState.COLLAPSED -> {
                when (SharedPreferencesHelper.isTripActive(applicationContext)) {
                    false -> {
                        removeMarkers()
                        tvSearch.text = getString(R.string.search_bar_title)
                        splMain.panelState = SlidingUpPanelLayout.PanelState.HIDDEN
                        ObjectAnimator.ofFloat(llFabButtons, "translationY", 0f).apply {
                            duration = 1000
                            start()
                        }
                        llSearchBar.isEnabled = true
                    }
                }
            }
            else -> super.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        when (SharedPreferencesHelper.isMainTheme(applicationContext)) {
            true -> {
                setTheme(R.style.AppTheme)
            }
            false -> {
                setTheme(R.style.AppThemeDriver)
            }
        }
        super.onCreate(savedInstanceState)
        initLayout()
        initFirebase()
        initNotificationReceiver()
        getLocationPermission()
        getDrawOverAppPermission()
        initLocationCallback()
        initOnClickListeners()
        //SharedPreferencesHelper.setTripActive(applicationContext, value = false) //TODO: For testing
    }

    private fun initNotificationReceiver() {
        notificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val driverId = intent.getStringExtra("driverId")
                val passengerId = intent.getStringExtra("passengerId")
                val notificationType = intent.getStringExtra("type")
                val personName = intent.getStringExtra("name")
                val meetingName = intent.getStringExtra("meetingName")
                this@MainActivity.passengerId = passengerId
                this@MainActivity.driverId = driverId
                this@MainActivity.meetingLat = intent.getStringExtra("meetingLat").toDouble()
                this@MainActivity.meetingLng = intent.getStringExtra("meetingLng").toDouble()
                SharedPreferencesHelper.setMeetingName(applicationContext, intent.getStringExtra("meetingName"))
                SharedPreferencesHelper.setMeetingLat(applicationContext, intent.getStringExtra("meetingLat"))
                SharedPreferencesHelper.setMeetingLng(applicationContext, intent.getStringExtra("meetingLng"))
                SharedPreferencesHelper.setDriverId(applicationContext, intent.getStringExtra("driverId"))
                SharedPreferencesHelper.setPassengerId(applicationContext, intent.getStringExtra("passengerId"))
                when (notificationType) {
                    NotificationType.ASK.name -> {
                        tvSplTitle.text = "Użytkownik $personName prosi o podwóżkę."
                        tvPassengerMeetingPoint.text = HtmlCompat.fromHtml("<b>Punkt spotkania: </b>ul. $meetingName", HtmlCompat.FROM_HTML_MODE_LEGACY)
                        llDrivers.visibility = View.GONE
                        llPassengers.visibility = View.VISIBLE
                        splMain.anchorPoint = 0.6f
                        ObjectAnimator.ofFloat(llFabButtons, "translationY", -splMain.panelHeight.toFloat()).apply {
                            duration = 300
                            start()
                        }
                        Handler().postDelayed(
                            {
                                splMain.panelState = SlidingUpPanelLayout.PanelState.ANCHORED
                            },300L
                        )
                    }
                    NotificationType.ACCEPT.name -> {
                        showProgressDialog()
                        splMain.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
                        controller.loadSpecificUser(SharedPreferencesHelper.getDriverId(applicationContext)!!)
                    }
                    NotificationType.DECLINE.name -> {
                        when (user.profile) {
                            Profile.DRIVER -> {
                                Toast.makeText(applicationContext, "Pasażer odrzucił prośbę!", Toast.LENGTH_LONG).show()
                                splMain.panelState = SlidingUpPanelLayout.PanelState.HIDDEN
                            }
                            Profile.PASSENGER -> {
                                waitingDialog?.dismiss()
                                Toast.makeText(applicationContext, "Kierowca anulował przjazd!", Toast.LENGTH_LONG).show()
                                splMain.panelState = SlidingUpPanelLayout.PanelState.ANCHORED
                            }
                        }
                    }
                    NotificationType.MEETING.name -> {
                        when (user.profile) {
                            Profile.DRIVER -> {
                                Toast.makeText(applicationContext, "Pasażer dotarł na miejsce spotkania!", Toast.LENGTH_LONG).show()
                            }
                            Profile.PASSENGER -> {
                                Toast.makeText(applicationContext, "Kierowca dojechał do punktu spotkania!", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initFirebase() {
        SharedPreferencesHelper.isFirebaseInit(applicationContext)?.let { isFirebaseInit ->
            if (!isFirebaseInit) {
                FirebaseInstanceId.getInstance().instanceId
                    .addOnCompleteListener(OnCompleteListener { task ->
                        if (!task.isSuccessful) {
                            Log.w("firebase-token", "getInstanceId failed", task.exception)
                            return@OnCompleteListener
                        }
                        val token = task.result?.token
                        Log.d("firebase-token", token)
                        token?.let{
                            controller.sendFirebaseToken(it)
                        }
                    })
            }
        }
    }

    private fun initLayout() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        navView.setNavigationItemSelectedListener(this)
        controller.loadUser()
    }

    private fun initOnClickListeners() {
        fabMyPosition.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                    applicationContext,
                    permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
                    if (location != null) {
                        lastLocation = location
                        val currentLatLng = LatLng(location.latitude, location.longitude)
                        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 12f))
                    }
                }
            }
        }
        toolbar.findViewById<ImageView>(R.id.ivProfile).setOnClickListener {
            MaterialDialog(this).show {
                customView(R.layout.popup_change_profile, scrollable = true)
                cancelOnTouchOutside(true)
                noAutoDismiss()
                negativeButton(R.string.cancel) {
                    dismiss()
                }
                positiveButton(R.string.confirm) {
                    dismiss()
                    controller.changeProfile()
                    when (SharedPreferencesHelper.isMainTheme(applicationContext)) {
                        true -> {
                            SharedPreferencesHelper.setMainTheme(applicationContext, false)
                            startActivity(Intent(applicationContext, MainActivity::class.java))
                            finish()
                        }
                        false -> {
                            SharedPreferencesHelper.setMainTheme(applicationContext, true)
                            startActivity(Intent(applicationContext, MainActivity::class.java))
                            finish()
                        }
                    }
                }
            }
        }
        llSearchBar.setOnClickListener {
            val intent = Autocomplete.IntentBuilder(
                AutocompleteActivityMode.FULLSCREEN, fields)
                .setTypeFilter(TypeFilter.ADDRESS)
                .setLocationBias(
                    RectangularBounds.newInstance(
                        LatLng(52.318536, 16.752789),
                        LatLng(52.474892, 17.059874)
                    )
                )
                .setCountry("PL")
                .build(this)
            startActivityForResult(intent, AUTOCOMPLETE_REQUEST_CODE)
        }
        btnAcceptDestination.setOnClickListener {
            requestingLocationUpdates = true
            startLocationUpdates()
            controller.setDestination(destination = destination)
            ObjectAnimator.ofFloat(llAcceptDestination, "translationY", -llAcceptDestination.height.toFloat()).apply {
                duration = 1000
                start()
            }
            ObjectAnimator.ofFloat(llHeaderBar, "translationY", -llHeaderBar.height.toFloat()).apply {
                duration = 1000
                start()
            }
            fabCancelRide.animate().alpha(1f).setDuration(1000).withStartAction {
                fabCancelRide.show()
            }
//            startService(
//                Intent(applicationContext, FloatingService::class.java).putExtra("message", "Serwus, to ja Twoj bombel!")
//            )
            btnAcceptDestination.isEnabled = false
            val navigationIntentUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + destination.lat +"," + destination.lng + "&travelmode=driving")
            val mapIntent = Intent(Intent.ACTION_VIEW, navigationIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            startActivity(mapIntent)
        }
        fabCancelRide.setOnClickListener {
            MaterialDialog(this).show {
                title(text = getString(R.string.popup_cancel_ride_title))
                cancelOnTouchOutside(true)
                noAutoDismiss()
                negativeButton(R.string.cancel) {
                    dismiss()
                }
                positiveButton(R.string.confirm) {
                    dismiss()
                    controller.finishDestination()
                    requestingLocationUpdates = false
                    stopLocationUpdates()
                    this@MainActivity.tvSearch.text = getString(R.string.search_bar_title)
                    removeMarkers()
                    ObjectAnimator.ofFloat(this@MainActivity.llAcceptDestination, "translationY", 0f).apply {
                        duration = 1000
                        start()
                    }
                    ObjectAnimator.ofFloat(this@MainActivity.llHeaderBar, "translationY", 0f).apply {
                        duration = 1000
                        start()
                    }
                    this@MainActivity.fabCancelRide.animate().alpha(0f).setDuration(1000).withEndAction {
                        this@MainActivity.fabCancelRide.hide()
                    }
                }
            }
        }
        btnAcceptPassenger.setOnClickListener {
            showProgressDialog()
            splMain.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
            controller.sendNotification(NotificationMessage(passengerId = SharedPreferencesHelper.getPassengerId(applicationContext)!!.toLong(), driverId = user.id, notificationType = NotificationType.ACCEPT))
            controller.loadSpecificUser(SharedPreferencesHelper.getPassengerId(context = applicationContext)!!)
        }
        btnCancelPassenger.setOnClickListener {
            controller.sendNotification(NotificationMessage(passengerId = SharedPreferencesHelper.getPassengerId(applicationContext)!!.toLong(), driverId = user.id, notificationType = NotificationType.DECLINE))
        }
        btnStartNavigation.setOnClickListener {
            val navigationIntentUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$meetingLat,$meetingLng&travelmode=walking")
            val mapIntent = Intent(Intent.ACTION_VIEW, navigationIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            startActivity(mapIntent)
        }
        btnFinishRide.setOnClickListener {
            removeMarkers()
            SharedPreferencesHelper.setTripActive(context = applicationContext, value = false)
            SharedPreferencesHelper.setMeetingName(context = applicationContext, value = null)
            SharedPreferencesHelper.setMeetingLat(context = applicationContext, value = null)
            SharedPreferencesHelper.setMeetingLng(context = applicationContext, value = null)
            SharedPreferencesHelper.setDriverId(context = applicationContext, value = null)
            SharedPreferencesHelper.setPassengerId(context = applicationContext, value = null)
            llInformation.visibility = View.GONE
            llDrivers.visibility = View.VISIBLE
            tvSearch.text = getString(R.string.search_bar_title)
            splMain.panelState = SlidingUpPanelLayout.PanelState.HIDDEN
            ObjectAnimator.ofFloat(llFabButtons, "translationY", 0f).apply {
                duration = 1000
                start()
            }
            llSearchBar.isEnabled = true
        }
        btnCancelRide.setOnClickListener {
            removeMarkers()
            SharedPreferencesHelper.setTripActive(context = applicationContext, value = false)
            SharedPreferencesHelper.setMeetingName(context = applicationContext, value = null)
            SharedPreferencesHelper.setMeetingLat(context = applicationContext, value = null)
            SharedPreferencesHelper.setMeetingLng(context = applicationContext, value = null)
            SharedPreferencesHelper.setDriverId(context = applicationContext, value = null)
            SharedPreferencesHelper.setPassengerId(context = applicationContext, value = null)
            llInformation.visibility = View.GONE
            llDrivers.visibility = View.VISIBLE
            tvSearch.text = getString(R.string.search_bar_title)
            splMain.panelState = SlidingUpPanelLayout.PanelState.HIDDEN
            ObjectAnimator.ofFloat(llFabButtons, "translationY", 0f).apply {
                duration = 1000
                start()
            }
            llSearchBar.isEnabled = true
        }
    }

    private fun initLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                locationResult ?: return
                for (location in locationResult.locations){
                    if (isDestinationReloadNeeded) {
                        controller.setDestination(destination = user.destination)
                        isDestinationReloadNeeded = false
                    }
                    if (!PolyUtil.isLocationOnPath(LatLng(location.latitude, location.longitude), routePoints, false, TOLERANCE)) {
                        isDestinationReloadNeeded = true
                        when(user.profile){
                            Profile.DRIVER -> {
                                controller.loadDirection(
                                    api = Connection.Builder().provideOkHttpClient(applicationContext).provideRetrofit(url = DIRECTIONS_URL).createApi(),
                                    origin = "${lastLocation.latitude},${lastLocation.longitude}",
                                    destination = "${SharedPreferencesHelper.getDestinationLat(applicationContext)!!.toDouble()},${SharedPreferencesHelper.getDestinationLng(applicationContext)!!.toDouble()}",
                                    travelMode = TRAVEL_MODE_DRIVING,
                                    key = BuildConfig.GOOGLE_API_KEY
                                )
                            }
                            Profile.PASSENGER -> {
                                controller.loadDirection(
                                    api = Connection.Builder().provideOkHttpClient(applicationContext).provideRetrofit(url = DIRECTIONS_URL).createApi(),
                                    origin = "${lastLocation.latitude},${lastLocation.longitude}",
                                    destination = "${SharedPreferencesHelper.getDestinationLat(applicationContext)!!.toDouble()},${SharedPreferencesHelper.getDestinationLng(applicationContext)!!.toDouble()}",
                                    travelMode = TRAVEL_MODE_WALKING,
                                    key = BuildConfig.GOOGLE_API_KEY
                                )
                            }
                        }
                    } else {
                        controller.sendPosition(Position(location.latitude, location.longitude))
                    }
                    if (SharedPreferencesHelper.getMeetingLat(applicationContext) != null && SharedPreferencesHelper.getMeetingLng(applicationContext) != null) {
                        val distance = FloatArray(2)
                        Location.distanceBetween(location.latitude, location.longitude, SharedPreferencesHelper.getMeetingLat(applicationContext)!!.toDouble(), SharedPreferencesHelper.getMeetingLng(applicationContext)!!.toDouble(), distance)
                        if (distance[0] <= RADIUS) {
                            requestingLocationUpdates = false
                            stopLocationUpdates()
                            controller.sendNotification(NotificationMessage(passengerId = SharedPreferencesHelper.getPassengerId(applicationContext)!!.toLong(), driverId = SharedPreferencesHelper.getDriverId(applicationContext)!!.toLong(), notificationType = NotificationType.MEETING))
                        }
                    }
                }
            }
        }
    }

    private fun initLocation(){
        val apiKey = packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA
        ).metaData.getString(
            "com.google.android.geo.API_KEY"
        )
        Places.initialize(applicationContext, apiKey!!)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest()
        locationRequest.interval = INTERVAL
        locationRequest.fastestInterval = INTERVAL
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(
                applicationContext,
                permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null /* Looper */
            )
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun placeDestinationMarkerOnMap(location: LatLng) {
        googleMap.clear()
        val markerOptions = MarkerOptions().position(location)
        googleMap.addMarker(markerOptions)
        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 12f))
    }

    private fun placeDriverMarkerOnMap(location: LatLng) {
        val markerOptions = MarkerOptions().position(location).icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_car_side))
        googleMap.addMarker(markerOptions)
    }

    private fun placePassengerMarkerOnMap(location: LatLng) {
        val markerOptions = MarkerOptions().position(location).icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_car_side))
        googleMap.addMarker(markerOptions)
    }

    private fun removeMarkers() {
        googleMap.clear()
    }

    @SuppressWarnings("CheckResult")
    private fun getLocationPermission() {
        val rxPermissions = RxPermissions(this)
        rxPermissions
            .request(permission.ACCESS_FINE_LOCATION)
            .subscribe{granted ->
                if (granted){
                    initLocation()
                }
            }
    }

    private fun getDrawOverAppPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }
}
