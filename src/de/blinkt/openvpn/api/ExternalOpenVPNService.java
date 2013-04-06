package de.blinkt.openvpn.api;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedList;
import java.util.List;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.VpnService;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ConfigParser.ConfigParseError;
import de.blinkt.openvpn.core.OpenVPN;
import de.blinkt.openvpn.core.OpenVPN.ConnectionStatus;
import de.blinkt.openvpn.core.OpenVPN.StateListener;
import de.blinkt.openvpn.core.OpenVpnService;
import de.blinkt.openvpn.core.OpenVpnService.LocalBinder;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;

public class ExternalOpenVPNService extends Service implements StateListener {

	final RemoteCallbackList<IOpenVPNStatusCallback> mCallbacks =
			new RemoteCallbackList<IOpenVPNStatusCallback>();

	private OpenVpnService mService;
	private ExternalAppDatabase mExtAppDb;	


	private ServiceConnection mConnection = new ServiceConnection() {


		@Override
		public void onServiceConnected(ComponentName className,
				IBinder service) {
			// We've bound to LocalService, cast the IBinder and get LocalService instance
			LocalBinder binder = (LocalBinder) service;
			mService = binder.getService();
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			mService =null;
		}

	};
	
	@Override
	public void onCreate() {
		super.onCreate();
		OpenVPN.addStateListener(this);
		mExtAppDb = new ExternalAppDatabase(this);

		Intent intent = new Intent(getBaseContext(), OpenVpnService.class);
		intent.setAction(OpenVpnService.START_SERVICE);

		bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
	}

	private final IOpenVPNAPIService.Stub mBinder = new IOpenVPNAPIService.Stub() {
		private boolean checkOpenVPNPermission()  throws SecurityRemoteException{
			PackageManager pm = getPackageManager();

			for (String apppackage:mExtAppDb.getExtAppList()) {
				ApplicationInfo app;
				try {
					app = pm.getApplicationInfo(apppackage, 0);
					if (Binder.getCallingUid() == app.uid) {
						return true;
					}
				} catch (NameNotFoundException e) {
					// App not found. Remove it from the list
					mExtAppDb.removeApp(apppackage);
					e.printStackTrace();
				}

			}
			throw new SecurityException("Unauthorized OpenVPN API Caller");
		}

		@Override
		public List<APIVpnProfile> getProfiles() throws RemoteException {
			checkOpenVPNPermission();

			ProfileManager pm = ProfileManager.getInstance(getBaseContext());

			List<APIVpnProfile> profiles = new LinkedList<APIVpnProfile>();

			for(VpnProfile vp: pm.getProfiles())
				profiles.add(new APIVpnProfile(vp.getUUIDString(),vp.mName,vp.mUserEditable));

			return profiles;
		}

		@Override
		public void startProfile(String profileUUID) throws RemoteException {
			checkOpenVPNPermission();

			Intent shortVPNIntent = new Intent(Intent.ACTION_MAIN);
			shortVPNIntent.setClass(getBaseContext(),de.blinkt.openvpn.LaunchVPN.class);
			shortVPNIntent.putExtra(de.blinkt.openvpn.LaunchVPN.EXTRA_KEY,profileUUID);
			shortVPNIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(shortVPNIntent);
		}

		public void startVPN(String inlineconfig) throws RemoteException {
			checkOpenVPNPermission();
			
			ConfigParser cp = new ConfigParser();
			try {
				cp.parseConfig(new StringReader(inlineconfig));
				VpnProfile vp = cp.convertProfile();
				if(vp.checkProfile(getApplicationContext()) != R.string.no_error_found)
					throw new RemoteException(getString(vp.checkProfile(getApplicationContext())));


				ProfileManager.setTemporaryProfile(vp);
				VPNLaunchHelper.startOpenVpn(vp, getBaseContext());


			} catch (IOException e) {
				throw new RemoteException(e.getMessage());
			} catch (ConfigParseError e) {
				throw new RemoteException(e.getMessage());
			}
		}

		@Override
		public boolean addVPNProfile(String name, String config) throws RemoteException {
			checkOpenVPNPermission();

			ConfigParser cp = new ConfigParser();
			try {
				cp.parseConfig(new StringReader(config));
				VpnProfile vp = cp.convertProfile();
				vp.mName = name;
				ProfileManager pm = ProfileManager.getInstance(getBaseContext());
				pm.addProfile(vp);
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			} catch (ConfigParseError e) {
				e.printStackTrace();
				return false;
			}

			return true;
		}


		@Override
		public Intent prepare(String packagename) {
			if (new ExternalAppDatabase(ExternalOpenVPNService.this).isAllowed(packagename))
				return null;
			
			Intent intent = new Intent();
			intent.setClass(ExternalOpenVPNService.this, ConfirmDialog.class);
			return intent;
		}

		@Override
		public boolean hasPermission() throws RemoteException {
			checkOpenVPNPermission();

			return VpnService.prepare(ExternalOpenVPNService.this)==null;
		}


		@Override
		public void registerStatusCallback(IOpenVPNStatusCallback cb)
				throws RemoteException {
			checkOpenVPNPermission();

			if (cb != null) mCallbacks.register(cb);


		}

		@Override
		public void unregisterStatusCallback(IOpenVPNStatusCallback cb)
				throws RemoteException {
			checkOpenVPNPermission();

			if (cb != null) mCallbacks.unregister(cb);

		}

		@Override
		public void disconnect() throws RemoteException {
			checkOpenVPNPermission();

			mService.getManagement().stopVPN();
		}
	};

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mCallbacks.kill();
		unbindService(mConnection);
		OpenVPN.removeStateListener(this);
	}

	@Override
	public void updateState(String state, String logmessage, int resid, ConnectionStatus level) {
		// Broadcast to all clients the new value.
		final int N = mCallbacks.beginBroadcast();
		for (int i=0; i<N; i++) {
			try {
				mCallbacks.getBroadcastItem(i).newStatus(state,logmessage);
			} catch (RemoteException e) {
				// The RemoteCallbackList will take care of removing
				// the dead object for us.
			}
		}
		mCallbacks.finishBroadcast();
	}



}