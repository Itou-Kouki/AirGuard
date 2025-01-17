package de.seemoo.at_tracking_detection.ui.tracking

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.transition.TransitionInflater
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.activity.addCallback
import androidx.cardview.widget.CardView
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import de.seemoo.at_tracking_detection.ATTrackingDetectionApplication
import de.seemoo.at_tracking_detection.R
import de.seemoo.at_tracking_detection.database.models.Location
import de.seemoo.at_tracking_detection.database.models.device.Connectable
import de.seemoo.at_tracking_detection.database.models.device.DeviceManager
import de.seemoo.at_tracking_detection.databinding.FragmentTrackingBinding
import de.seemoo.at_tracking_detection.ui.MainActivity
import de.seemoo.at_tracking_detection.util.Utility
import de.seemoo.at_tracking_detection.util.ble.BluetoothConstants
import de.seemoo.at_tracking_detection.util.ble.BluetoothLeService
import kotlinx.coroutines.launch
import org.osmdroid.views.MapView
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class TrackingFragment : Fragment() {

    @Inject
    lateinit var trackingViewModel: TrackingViewModel

    private var notificationId: Int = -1

    private val safeArgs: TrackingFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding: FragmentTrackingBinding =
            DataBindingUtil.inflate(inflater, R.layout.fragment_tracking, container, false)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.vm = trackingViewModel
        val notifId = safeArgs.notificationId
        val deviceAddress = safeArgs.deviceAddress
        trackingViewModel.notificationId.postValue(notifId)
        trackingViewModel.deviceAddress.postValue(deviceAddress)
        trackingViewModel.loadDevice(safeArgs.deviceAddress)
        trackingViewModel.notificationId.observe(viewLifecycleOwner) {
            notificationId = it
        }

        sharedElementEnterTransition =
            TransitionInflater.from(context).inflateTransition(android.R.transition.move)
                .setInterpolator(LinearOutSlowInInterpolator()).setDuration(500)
        postponeEnterTransition(100, TimeUnit.MILLISECONDS)

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        val activity = ATTrackingDetectionApplication.getCurrentActivity() ?: return

        LocalBroadcastManager.getInstance(activity)
            .registerReceiver(gattUpdateReceiver, DeviceManager.gattIntentFilter)
        activity.registerReceiver(gattUpdateReceiver, DeviceManager.gattIntentFilter)
    }

    override fun onPause() {
        super.onPause()
        context?.unregisterReceiver(gattUpdateReceiver)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity?.onBackPressedDispatcher?.addCallback(this) {
            if (safeArgs.notificationId != -1) {
                val intent = Intent(context, MainActivity::class.java)
                intent.apply {
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            } else {
                findNavController().navigateUp()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val feedbackButton = view.findViewById<CardView>(R.id.tracking_feedback)
        val playSoundCard = view.findViewById<CardView>(R.id.tracking_play_sound)
        val map = view.findViewById<MapView>(R.id.map)

        feedbackButton.setOnClickListener {
            val directions: NavDirections =
                TrackingFragmentDirections.actionTrackingFragmentToFeedbackFragment(notificationId)
            findNavController().navigate(directions)
        }

        playSoundCard.setOnClickListener {
            if (!Utility.checkAndRequestPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
                return@setOnClickListener
            }

            val baseDevice = trackingViewModel.device.value
            if (baseDevice != null && baseDevice.device is Connectable) {
                toggleSound()
            } else {
                Snackbar.make(
                    view,
                    getString(R.string.tracking_device_not_connectable),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        Utility.enableMyLocationOverlay(map)

        trackingViewModel.markerLocations.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                trackingViewModel.isMapLoading.postValue(true)

                val locationList = arrayListOf<Location>()
                val locationRepository = ATTrackingDetectionApplication.getCurrentApp()?.locationRepository!!

                it.filter { it.locationId != null && it.locationId != 0 }
                    .map {
                        val location = locationRepository.getLocationWithId(it.locationId!!)
                        if (location != null) {
                            locationList.add(location)
                        }
                    }

                // This is the per Device View
                Utility.setGeoPointsFromListOfLocations(locationList.toList(), map, true)
            }.invokeOnCompletion {
                trackingViewModel.isMapLoading.postValue(false)
            }
        }
        trackingViewModel.soundPlaying.observe(viewLifecycleOwner) {
            if (!it) {
                try {
                    requireContext().unbindService(serviceConnection)
                } catch (e: IllegalArgumentException) {
                    Timber.e("Tried to unbind an unbound service!")
                } finally {
                    Timber.d("Service connection unregistered")
                }
            }
        }

        addInteractions(view)
    }

    fun addInteractions(view: View) {
        val button = view.findViewById<ImageButton>(R.id.open_map_button)


        button.setOnClickListener {
            val direction = TrackingFragmentDirections.actionTrackingFragmentToDeviceMapFragment(showAllDevices = false, deviceAddress = trackingViewModel.deviceAddress.value)
            findNavController().navigate(direction)
        }

        val overlay = view.findViewById<View>(R.id.map_overlay)
        overlay.setOnClickListener {
            val direction = TrackingFragmentDirections.actionTrackingFragmentToDeviceMapFragment(showAllDevices = false, deviceAddress = trackingViewModel.deviceAddress.value)
            findNavController().navigate(direction)
        }


    }

    private fun toggleSound() {
        trackingViewModel.error.postValue(false)
        if (trackingViewModel.soundPlaying.value == false) {
            trackingViewModel.connecting.postValue(true)
            val gattServiceIntent = Intent(context, BluetoothLeService::class.java)
            requireContext().bindService(
                gattServiceIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } else {
            Timber.d("Sound already playing! Stopping sound...")
            trackingViewModel.soundPlaying.postValue(false)
        }
    }

    private val gattUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothConstants.ACTION_EVENT_RUNNING -> {
                    trackingViewModel.soundPlaying.postValue(
                        true
                    )
                    trackingViewModel.connecting.postValue(false)
                }
                BluetoothConstants.ACTION_GATT_DISCONNECTED -> {
                    trackingViewModel.soundPlaying.postValue(false)
                    trackingViewModel.connecting.postValue(false)
                }
                BluetoothConstants.ACTION_EVENT_FAILED -> {
                    trackingViewModel.error.postValue(true)
                    trackingViewModel.connecting.postValue(false)
                    trackingViewModel.soundPlaying.postValue(false)
                }
                BluetoothConstants.ACTION_EVENT_COMPLETED -> trackingViewModel.soundPlaying.postValue(
                    false
                )
            }
        }
    }

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Timber.d("Trying to connect to ble device!")
            val bluetoothService = (service as BluetoothLeService.LocalBinder).getService()
            bluetoothService.let {
                if (!it.init()) {
                    Timber.e("Unable to init bluetooth")
                    trackingViewModel.error.postValue(true)
                } else {
                    Timber.d("Device is ready to connect!")
                    trackingViewModel.device.observe(viewLifecycleOwner) { baseDevice ->
                        it.connect(baseDevice)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingViewModel.soundPlaying.postValue(false)
            trackingViewModel.connecting.postValue(false)
        }
    }
}