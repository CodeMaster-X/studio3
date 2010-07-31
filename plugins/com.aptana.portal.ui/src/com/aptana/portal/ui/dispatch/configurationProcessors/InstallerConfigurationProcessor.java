package com.aptana.portal.ui.dispatch.configurationProcessors;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.mortbay.util.ajax.JSON;
import org.osgi.framework.Version;

import com.aptana.configurations.processor.AbstractConfigurationProcessor;
import com.aptana.configurations.processor.ConfigurationStatus;
import com.aptana.core.util.InputStreamGobbler;
import com.aptana.core.util.StringUtil;
import com.aptana.ide.core.io.downloader.DownloadManager;
import com.aptana.portal.ui.IPortalPreferences;
import com.aptana.portal.ui.PortalUIPlugin;
import com.aptana.portal.ui.dispatch.processorDelegates.BaseVersionProcessor;

/**
 * Basic, abstract implementation, of a processor that deals with installing software.
 * 
 * @author Shalom Gibly <sgibly@aptana.com>
 */
public abstract class InstallerConfigurationProcessor extends AbstractConfigurationProcessor
{
	protected static final String APTANA_PROPERTIES_FILE_NAME = ".aptana"; //$NON-NLS-1$
	protected static final String WINDOWS_7ZIP_EXECUTABLE = "$os$/7za.exe"; //$NON-NLS-1$
	protected String[] downloadedPaths;
	protected String[] urls;

	/*
	 * (non-Javadoc)
	 * @see com.aptana.configurations.processor.AbstractConfigurationProcessor#computeStatus(org.eclipse.core.runtime.
	 * IProgressMonitor, java.lang.Object)
	 */
	@Override
	public ConfigurationStatus computeStatus(IProgressMonitor progressMonitor, Object attributes)
	{
		// This one does nothing. We should compute the status in the generic VersionsConfigurationProcessor
		return configurationStatus;
	}

	/**
	 * Returns the application name.
	 * 
	 * @return The application name (e.g. XAMPP, Ruby)
	 */
	protected abstract String getApplicationName();

	/**
	 * Download the remote content and store it the temp directory.
	 * 
	 * @param URLs
	 * @param progressMonitor
	 */
	public IStatus download(Object[] URLs, IProgressMonitor progressMonitor)
	{
		downloadedPaths = null;
		DownloadManager downloadManager = new DownloadManager();
		urls = new String[URLs.length];
		List<URL> urlsList = new ArrayList<URL>(URLs.length);
		for (int i = 0; i < URLs.length; i++)
		{
			try
			{
				Object o = URLs[i];
				urls[i] = o.toString();
				urlsList.add(new URL(urls[i]));
			}
			catch (MalformedURLException mue)
			{
				PortalUIPlugin.logError(mue);
			}
		}
		try
		{
			downloadManager.addURLs(urlsList);
			IStatus status = downloadManager.start(progressMonitor);
			if (status.isOK())
			{
				downloadedPaths = downloadManager.getContentsLocations();
			}
			return status;
		}
		catch (Exception e)
		{
			PortalUIPlugin.logError(e);
		}
		return Status.CANCEL_STATUS;
	}

	/**
	 * Cache the installed application location and version in the preferences.
	 * 
	 * @param installDir
	 *            - The directory the application was installed to.
	 * @param versionedFileLocation
	 *            - Can be the URL that we grabbed the installer from, or any other string that contains a version
	 *            information in a form of x.y.z.
	 * @param appName
	 *            - The application name (e.g. xampp)
	 */
	@SuppressWarnings("unchecked")
	public void cacheVersion(String installDir, String versionedFileLocation, String appName)

	{
		IPreferenceStore preferenceStore = PortalUIPlugin.getDefault().getPreferenceStore();
		String versions = preferenceStore.getString(IPortalPreferences.CACHED_VERSIONS_PROPERTY_NAME);
		Map<String, Map<String, String>> versionsMap = null;
		if (versions == null || versions.equals(StringUtil.EMPTY))
		{
			versionsMap = new HashMap<String, Map<String, String>>();
		}
		else
		{
			versionsMap = (Map<String, Map<String, String>>) JSON.parse(versions);
		}
		Map<String, String> appVersionMap = new HashMap<String, String>();
		Version version = BaseVersionProcessor.parseVersion(versionedFileLocation);
		if (version != null)
		{
			appVersionMap.put(IPortalPreferences.CACHED_VERSION_PROPERTY, version.toString());
			appVersionMap.put(IPortalPreferences.CACHED_LOCATION_PROPERTY, installDir);
			versionsMap.put(appName.toLowerCase(), appVersionMap);
			preferenceStore.setValue(IPortalPreferences.CACHED_VERSIONS_PROPERTY_NAME, JSON.toString(versionsMap));
		}
		else
		{
			PortalUIPlugin.logError("Could not cache the location and version for " + appName + ". Install dir: " //$NON-NLS-1$ //$NON-NLS-2$
					+ installDir + ", versionedFileLocation: " + versionedFileLocation, new Exception()); //$NON-NLS-1$
		}
	}

	/**
	 * Extract the given zip file into the target folder on a Windows machine.
	 * 
	 * @param zipFile
	 * @param targetFolder
	 * @return The status of that extraction result.
	 */
	public static IStatus extractWin(String zipFile, String targetFolder)
	{
		IStatus errorStatus = new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID,
				Messages.InstallerConfigurationProcessor_unableToExtractZip);
		if (!Platform.OS_WIN32.equals(Platform.getOS()))
		{
			PortalUIPlugin
					.logError(
							"Unable to extract the Zip file. A Windows OS extractor was called for a non-Windows platform.", new Exception()); //$NON-NLS-1$
			return errorStatus;
		}
		if (zipFile == null || targetFolder == null)
		{
			PortalUIPlugin.logError("Undefined zip file or target folder", new Exception()); //$NON-NLS-1$
			return errorStatus;
		}
		IPath zipExecutable = getBundlePath(WINDOWS_7ZIP_EXECUTABLE);
		File destinationFolder = new File(targetFolder);
		if (!destinationFolder.exists() && !destinationFolder.mkdirs())
		{
			PortalUIPlugin.logError("Failed to create destination directory " + destinationFolder, new Exception()); //$NON-NLS-1$
			return errorStatus;
		}
		ProcessBuilder processBuilder = new ProcessBuilder(zipExecutable.toOSString(), "x", //$NON-NLS-1$
				"-o" + targetFolder, //$NON-NLS-1$
				"-y", //$NON-NLS-1$
				zipFile);
		processBuilder.directory(destinationFolder);
		processBuilder.redirectErrorStream(true);
		String output = null;
		try
		{
			Process process = processBuilder.start();
			InputStreamGobbler errorGobbler = new InputStreamGobbler(process.getErrorStream(), "\n", null); //$NON-NLS-1$
			InputStreamGobbler outputGobbler = new InputStreamGobbler(process.getInputStream(), "\n", null); //$NON-NLS-1$
			outputGobbler.start();
			errorGobbler.start();
			process.waitFor();
			outputGobbler.interrupt();
			errorGobbler.interrupt();
			outputGobbler.join();
			errorGobbler.join();
			output = outputGobbler.getResult();
			String errors = errorGobbler.getResult();
			int exitVal = process.exitValue();
			if (exitVal == 0)
			{
				return Status.OK_STATUS;
			}
			else
			{
				PortalUIPlugin
						.logError(
								"Zip extraction failed. The process returned " + exitVal, new Exception("Process output:\n" + errors)); //$NON-NLS-1$ //$NON-NLS-2$
				return errorStatus;
			}
		}
		catch (Exception e)
		{
			PortalUIPlugin.logError(e);
			return errorStatus;
		}
		finally
		{
			if (output != null)
			{
				PortalUIPlugin.logInfo(output, null);
			}
		}
	}

	/**
	 * Display a message dialog in a UI thread.
	 * 
	 * @param kind
	 *            See {@link MessageDialog} for the types allowed.
	 * @param title
	 * @param message
	 */
	public void displayMessageInUIThread(final int kind, final String title, final String message)
	{
		Display.getDefault().syncExec(new Runnable()
		{
			@Override
			public void run()
			{
				MessageDialog.open(kind, null, title, message, SWT.NONE);
			}
		});
	}

	/**
	 * Finalize the installation. <br>
	 * This implementation just marks to delete on exit any downaloaded file.
	 * 
	 * @param installDir
	 */
	protected void finalizeInstallation(String installDir)
	{
		if (downloadedPaths != null)
		{
			for (String f : downloadedPaths)
			{
				File toDelete = new File(f);
				if (toDelete.exists())
				{
					toDelete.deleteOnExit();
				}
			}
		}
		// Cache the version and the location of the installed XAMPP.
		// We assume here that the version of XAMPP is specified in the install URL!
		cacheVersion(installDir, urls[0], getApplicationName());
	}

	/*
	 * Returns an IPath from the given portable string.
	 */
	private static IPath getBundlePath(String path)
	{
		URL url = FileLocator.find(PortalUIPlugin.getDefault().getBundle(), Path.fromPortableString(path), null);
		if (url != null)
		{
			try
			{
				url = FileLocator.toFileURL(url);
				File file = new File(url.getPath());
				if (file.exists())
				{
					return Path.fromOSString(file.getAbsolutePath());
				}
			}
			catch (IOException e)
			{
				PortalUIPlugin.logError(e);
			}
		}
		return null;
	}
}
