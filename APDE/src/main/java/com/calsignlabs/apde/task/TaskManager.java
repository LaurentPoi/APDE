package com.calsignlabs.apde.task;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.view.View;

import com.calsignlabs.apde.APDE;
import com.calsignlabs.apde.R;
import com.calsignlabs.apde.support.CustomProgressDialog;

import java.util.HashMap;

public class TaskManager {
	private APDE context;
	
	private HashMap<String, Task> tasks;
	private HashMap<Task, Thread> taskThreads;
	
	public TaskManager(APDE context) {
		this.context = context;
		
		tasks = new HashMap<String, Task>();
		taskThreads = new HashMap<Task, Thread>();
	}
	
	/**
	 * Register and start a new task.
	 * 
	 * @param tag the task's tag
	 * @param foreground true if the task should start in the foreground, false if it should start in the background
	 * @param activityContext if running in the foreground, the activity context (may be null otherwise)
	 * @param replaceExisting whether or not this task should replace an existing task with the same tag
	 * @param task the task
	 */
	public void launchTask(String tag, boolean foreground, Activity activityContext, boolean replaceExisting, Task task) {
		if (containsTask(tag)) {
			if (replaceExisting) {
				unregisterTask(tag);
			} else {
				//That's a no-no
				return;
			}
		}
		
		registerTask(tag, task);
		
		if (foreground) {
			//For long tasks
			startForegroundTask(tag, activityContext);
		} else {
			//For quick tasks
			startBackgroundTask(tag);
		}
	}
	
	public boolean containsTask(String tag) {
		return tasks.containsKey(tag);
	}
	
	public void registerTask(String tag, Task task) {
		if (tasks.containsKey(tag)) {
			//That's a no-no
			System.err.println("can't register task  \"" + tag + "\", tag already exists");
			return;
		}
		
		task.init(context);
		
		tasks.put(tag, task);
	}
	
	public void unregisterTask(String tag) {
		if (tasks.get(tag).isRunning()) {
			//That's a no-no
			System.err.println("can't unregister running task \"" + tag + "\", must be killed first");
			return;
		}
		
		tasks.remove(tag);
	}
	
	public Task getTask(String tag) {
		if (!tasks.containsKey(tag)) {
			System.err.println("can't get task  \"" + tag + "\", tag doesn't exist");
			return null;
		}

		return tasks.get(tag);
	}
	
	public void startBackgroundTask(String tag) {
		final Task task = getTask(tag);

		if (task.isRunning()) {
			//That's a no-no
			System.err.println("can't start task  \"" + tag + "\", already running");
			return;
		}

		moveToBackground(task);
		startTask(task);
	}
	
	public void startForegroundTask(String tag, Activity activityContext) {
		final Task task = getTask(tag);
		
		if (task.isRunning()) {
			//That's a no-no
			System.err.println("can't start task  \"" + tag + "\", already running");
			return;
		}

		moveToForeground(task, activityContext);
		startTask(task);
	}
	
	private void startTask(final Task task) {
		if (task.isRunning()) {
			//That's a no-no
			System.err.println("can't start task, already running");
			return;
		}
		
		Thread taskThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					task.start();
					task.run();
					task.stop();
				} catch (Exception e) {
					e.printStackTrace();
					
					//Try to close resources...
					try {
						task.stop();
					} catch (Exception e2) {
						e2.printStackTrace();
					}
				}
			}
		});
		
		taskThreads.put(task, taskThread);
		
		taskThread.start();
	}
	
	public void moveToBackground(final Task task) {
		TaskStatusRelay statusRelay = new BackgroundTaskRelay(context.getEditor());
		
		TaskStatusRelay previousStatusRelay = task.getStatusRelay();
		if (previousStatusRelay != null) {
			statusRelay.setStatusHistory(previousStatusRelay.getStatusHistory());
			previousStatusRelay.close();
		}
		
		task.setStatusRelay(statusRelay);
	}
	
	public void moveToForeground(final Task task, Activity activityContext) {
		CustomProgressDialog progressDialog = new CustomProgressDialog(activityContext, View.GONE, View.GONE);
		progressDialog.setTitle(task.getTitle());
		if (task.getMessage() != null) {
			progressDialog.setMessage(task.getMessage());
		}
		
		progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		progressDialog.setIndeterminate(true);
		progressDialog.setCanceledOnTouchOutside(false);
		
		progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, context.getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				cancelTask(task);
			}
		});
		
		if (task.canRunInBackground()) {
			progressDialog.setButton(DialogInterface.BUTTON_NEUTRAL, context.getResources().getString(R.string.run_in_background), new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					moveToBackground(task);
				}
			});
		}
		
		TaskStatusRelay statusRelay = new ForegroundStatusRelay(this, progressDialog);
		
		TaskStatusRelay previousStatusRelay = task.getStatusRelay();
		if (previousStatusRelay != null) {
			statusRelay.setStatusHistory(previousStatusRelay.getStatusHistory());
			previousStatusRelay.close();
		}
		
		task.setStatusRelay(statusRelay);
		
		progressDialog.show();
	}
	
	public void killTask(Task task) {
		if (!task.isRunning()) {
			//That's a no-no
			System.err.println("can't kill task, not running");
			return;
		}
		
		taskThreads.get(task).interrupt();
		taskThreads.remove(task);
		task.stop();
	}
	
	public void cancelTask(Task task) {
		if (!task.isRunning()) {
			//That's a no-no
			System.err.println("can't kill task, not running");
			return;
		}
		
		taskThreads.get(task).interrupt();
		taskThreads.remove(task);
		task.cancel();
		task.stop();
	}
	
	public void runOnUiThread(Runnable runnable) {
		context.getEditor().runOnUiThread(runnable);
	}
}
