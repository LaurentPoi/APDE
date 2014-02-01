package com.calsignlabs.apde;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import processing.data.XML;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBar.Tab;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.internal.widget.ScrollingTabContainerView.TabView;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.AbsListView.OnScrollListener;

import com.calsignlabs.apde.build.Build;
import com.calsignlabs.apde.build.Manifest;
import com.calsignlabs.apde.support.PopupMenu;
import com.calsignlabs.apde.support.ScrollingTabContainerView;

/**
 * This is the editor, or the main activity of APDE
 */
public class EditorActivity extends ActionBarActivity implements ScrollingTabContainerView.TabListener {
	//List of key bindings for hardware / bluetooth keyboards
	private HashMap<String, KeyBinding> keyBindings;
	
	//Custom tab implementation - EVERYTHING ABOUT THIS IS HACKY
	public ScrollingTabContainerView tabBar;
	
	//List of tabs
	private HashMap<Tab, FileMeta> tabs;
	
	//Whether or not the sketch has been saved TODO this isn't even being used right now
	private boolean saved;
	
	//Used for accessing the sliding drawer that contains the "Sketchbook"
	private ActionBarDrawerToggle drawerToggle;
	private boolean drawerOpen;
	
	//Used for adjusting the display to accomodate for the keyboard
	private boolean keyboardVisible;
	private boolean firstResize = true; //this is a makeshift arrangement (hopefully)
	private int oldCodeHeight = -1;
	
	//The height of the message area TODO make this non-constant in case there's a two-line error message, for example
	private int message;
	
	//Possible dialog results
	private final static int RENAME_TAB = 0;
	private final static int NEW_TAB = 1;
	
	//Listener for managing the sliding message area
	private MessageTouchListener messageListener;
	
	//Custom output / error streams for printing to the console
	private PrintStream outStream;
	private PrintStream errStream;
	
	//Whether or not we are currently building a sketch
	private boolean building;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        
        //Initialize the custom tab bar
        tabBar = new ScrollingTabContainerView(getSupportActionBar().getThemedContext(), this);
        
        //Set the tab bar to the proper height
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.support.v7.appcompat.R.attr.actionBarSize, tv, true);
        int actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        tabBar.setContentHeight(actionBarHeight);
        
        //Add the tab bar at the top of the layout
        ((LinearLayout) findViewById(R.id.content)).addView(tabBar, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        tabBar.setAllowCollapse(false);
        
        //Set the tab bar background color
        tabBar.setBackgroundColor(getResources().getColor(R.color.bar_overlay));
        
        //Load all of the permissions
        Manifest.loadPermissions(this);
        
        //Initialize the sliding message area listener
        messageListener = new MessageTouchListener();
        
        //Enable the message area listener
        findViewById(R.id.message).setOnLongClickListener(messageListener);
        findViewById(R.id.message).setOnTouchListener(messageListener);
        
        //Initialize the list of tabs
        tabs = new HashMap<Tab, FileMeta>();
        
        //The sketch is not saved TODO what's going on here, really?
        setSaved(false);
        
        //Initialize the global APDE application object
        getGlobalState().setEditor(this);
        getGlobalState().setSketchName("sketch");
        getGlobalState().setSelectedSketch(-1);
        
        //Initialize the action bar title
        getSupportActionBar().setTitle(getGlobalState().getSketchName());
        
        //Try to load the auto-save sketch, otherwise set the editor up as a new sketch
        if(!loadSketchStart())
        	addDefaultTab(getGlobalState().getSketchName());
        
        //Make the code area able to detect its own text changing
        ((CodeEditText) findViewById(R.id.code)).setupTextListener();
        
        //Detect text changes for determining whether or not the sketch has been saved
        ((EditText) findViewById(R.id.code)).addTextChangedListener(new TextWatcher(){
            public void afterTextChanged(Editable s) {
                if(isSaved()) setSaved(false);
            }
            public void beforeTextChanged(CharSequence s, int start, int count, int after){}
            public void onTextChanged(CharSequence s, int start, int before, int count){}
        });
        
        //Detect touch events
        ((EditText) findViewById(R.id.code)).setOnTouchListener(new EditText.OnTouchListener() {
        	@Override
        	public boolean onTouch(View v, MotionEvent event) {
        		//Disable the soft keyboard if the user is using a hardware keyboard
        		if(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("use_hardware_keyboard", false))
        			getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        		
        		return false;

        	}
        });
        
        //Check for an app update
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int oldVersionNum = prefs.getInt("version_num", -1);
        int realVersionNum = getGlobalState().appVersionCode();
        
        if(realVersionNum > oldVersionNum) {
        	//We need to update the examples (in case the new release has added more)
        	//This is some serious future-proofing... boy am I paranoid...
        	
        	copyAssetFolder(getAssets(), "examples", getGlobalState().getExamplesFolder().getAbsolutePath());
        	
        	//Make sure to update the value so we don't do this again
        	SharedPreferences.Editor edit = prefs.edit();
        	edit.putInt("version_num", realVersionNum);
        	edit.commit();
        }
        
        //Initialize the navigation drawer
        final DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer);
        final ListView drawerList = (ListView) findViewById(R.id.drawer_list);
        
        //Populate the drawer
        forceDrawerReload();
        
        //Detect drawer sliding events
        drawerList.setOnScrollListener(new OnScrollListener() {
			@Override
			public void onScroll(AbsListView arg0, int arg1, int arg2, int arg3) {}
			
			@Override
			public void onScrollStateChanged(AbsListView listView, int scrollState) {
				if(scrollState == SCROLL_STATE_IDLE)
					//Select the current sketch
                    if(getGlobalState().getSelectedSketch() < drawerList.getCount() && getGlobalState().getSelectedSketch() >= 0)
                    	drawerList.setItemChecked(getGlobalState().getSelectedSketch(), true);
        }});
        
        //Initialize the drawer drawer toggler
        drawerToggle = new ActionBarDrawerToggle(this, drawer, R.drawable.ic_navigation_drawer, R.string.nav_drawer_open, R.string.nav_drawer_close) {
            @Override
        	public void onDrawerClosed(View view) {
            	((EditText) findViewById(R.id.code)).setEnabled(true);
                supportInvalidateOptionsMenu();
            }
            
            @Override
            public void onDrawerSlide(View drawer, float slide) {
            	super.onDrawerSlide(drawer, slide);
            	
            	if(slide > 0) { //Detect an open event
            		((EditText) findViewById(R.id.code)).setEnabled(false);
                    supportInvalidateOptionsMenu();
                    drawerOpen = true;
                    
                    //Select the current sketch in the drawer
                    if(getGlobalState().getSelectedSketch() < drawerList.getCount() && getGlobalState().getSelectedSketch() >= 0) {
                    	ListView drawerList = (ListView) findViewById(R.id.drawer_list);
                    	View view = drawerList.getChildAt(getGlobalState().getSelectedSketch());
                    	if(view != null)
                    		view.setSelected(true);
                    }
            	} else { //Detect a close event
            		//Re-enable the code area
            		((EditText) findViewById(R.id.code)).setEnabled(true);
                    supportInvalidateOptionsMenu();
                    drawerOpen = false;
            	}
            }
            
            @Override
            public void onDrawerOpened(View drawerView) {
            	((EditText) findViewById(R.id.code)).setEnabled(false);
                supportInvalidateOptionsMenu();
        }};
        drawer.setDrawerListener(drawerToggle);
        
        //Detect drawer sketch selection events
        drawerList.setOnItemClickListener(new ListView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				//Save the current sketch
				autoSave();
				
				//Load the selected sketch
				String sketchName = ((TextView) view).getText().toString();
				
				//If it is further down on the list, it must be an example
				if(position > getSketchCount() + 1)
					loadExample(sketchName);
				else
					loadSketch(sketchName);
				
				drawerList.setItemChecked(position, true);
				getGlobalState().setSelectedSketch(position);
				
				//Close the drawer
				drawer.closeDrawers();
		}});
        
        //Enable the home button, the "home as up" will actually get replaced by the drawer toggle button
        getSupportActionBar().setHomeButtonEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        
        final ScrollView codeScroller = (ScrollView) findViewById(R.id.code_scroller);
        final EditText code = (EditText) findViewById(R.id.code);
        
        //Forward touch events to the code area so that the user can select anywhere
        codeScroller.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				HorizontalScrollView codeScrollerX = (HorizontalScrollView) findViewById(R.id.code_scroller_x);
				LinearLayout padding = (LinearLayout) findViewById(R.id.code_padding);
				
				if(code.getHeight() < codeScroller.getHeight() - padding.getPaddingBottom() + padding.getPaddingTop()) {
					//Hacky - dispatches a touch event to the code area
					
					MotionEvent me = MotionEvent.obtain(event);
					//Accommodate for scrolling
					me.setLocation(me.getX() + codeScrollerX.getScrollX(), me.getY());
					code.dispatchTouchEvent(me);
					me.recycle();

					code.requestFocus();
				}
				
				//Make sure that the scroll area can still scroll
				return false;
		}});
        
        //Obtain the root view
        final View activityRootView = findViewById(R.id.content);
        
        //Make the code area fill the width of the screen
        code.setMinimumWidth(activityRootView.getWidth());
        code.setMinWidth(activityRootView.getWidth());
        
        //Detect software keyboard open / close events
        //StackOverflow: http://stackoverflow.com/questions/2150078/how-to-check-visibility-of-software-keyboard-in-android
        activityRootView.getViewTreeObserver().addOnGlobalLayoutListener(new OnGlobalLayoutListener() {
        	@SuppressWarnings("deprecation")
			@Override
        	public void onGlobalLayout() {
        		//Calculate the difference in height
        		Rect r = new Rect();
        		activityRootView.getWindowVisibleDisplayFrame(r);
        		int heightDiff = activityRootView.getRootView().getHeight() - (r.bottom - r.top);
        		
        		//Hide the soft keyboard if it's trying to show its dirty face...
        		//...and the user doesn't want it
        		if(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("use_hardware_keyboard", false)) {
        			InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        			imm.hideSoftInputFromWindow(activityRootView.getWindowToken(), 0);
        			return;
        		}
        		
        		if(oldCodeHeight == -1)
        			oldCodeHeight = findViewById(R.id.code_scroller).getHeight();
        		
        		if(heightDiff > 100) { //If the difference is bigger than 100, it's probably the keyboard
        			if(!keyboardVisible) {
	        			keyboardVisible = true;
	        			
	        			//If the message height hasn't been retrieved yet, retrieve it
	        			if(message == 0)
							message = findViewById(R.id.message).getHeight();
	        			
	        			//Configure the layout for the keyboard
	        			
	        			View messageArea = findViewById(R.id.message);
        				View console = findViewById(R.id.console_scroller);
        				View code = findViewById(R.id.code_scroller);
	        			View content = findViewById(R.id.content);
	        			
	        			if(firstResize)
	        				firstResize = false;
	        			else
	        				oldCodeHeight = code.getHeight();
	        			
	        			//Start the custom animation TODO make the keyboard appearance prettier
	        			messageArea.startAnimation(new MessageAreaAnimation(code, console, messageArea, oldCodeHeight, content.getHeight() - message  - tabBar.getHeight(), content.getHeight()));
	        			
	        			//Remove the focus from the Message slider if it has it
	        			messageArea.setBackgroundDrawable(getResources().getDrawable(R.drawable.back)); //TODO this is deprecated...
	        			
	        			((CodeEditText) findViewById(R.id.code)).updateBracketMatch();
        			}
        		} else {
        			if(keyboardVisible) {
        				//Configure the layout for the absence of the keyboard
        				
        				View messageArea = findViewById(R.id.message);
        				View codeArea = findViewById(R.id.code_scroller);
        				View consoleArea = findViewById(R.id.console_scroller);
        				
        				//Start the custom animation TODO make the keyboard appearance prettier
        				messageArea.startAnimation(new MessageAreaAnimation(codeArea, consoleArea, messageArea, codeArea.getLayoutParams().height, oldCodeHeight, findViewById(R.id.content).getHeight()  - tabBar.getHeight()));
        				
	        			keyboardVisible = false;
	        			
	        			//Remove any unnecessary focus from the code area
	        			((CodeEditText) findViewById(R.id.code)).clearFocus();
	        			((CodeEditText) findViewById(R.id.code)).matchingBracket = -1;
        			}
        		}
        	}
        });
        
        //Detect orientation changes
        (new OrientationEventListener(this) { //TODO make rotation changes prettier
        	@SuppressLint("NewApi")
        	@SuppressWarnings("deprecation")
        	public void onOrientationChanged(int orientation) {
        		//If we don't know what this is, bail out
        		if(orientation == ORIENTATION_UNKNOWN)
        			return;
        		
        		int minWidth;
        		int maxWidth;
        		
        		//Let's try and do things correctly for once
        		if(android.os.Build.VERSION.SDK_INT >= 13) {
        			Point point = new Point();
        			getWindowManager().getDefaultDisplay().getSize(point);
        			maxWidth = point.x;
        		} else {
        			maxWidth = getWindowManager().getDefaultDisplay().getWidth();
        		}

        		//Remove padding
        		minWidth = maxWidth - (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics()) * 2;

        		//Make sure that the EditText is wide enough
        		findViewById(R.id.code).setMinimumWidth(minWidth);
        		findViewById(R.id.console).setMinimumWidth(minWidth);

        		findViewById(R.id.code_scroller_x).setLayoutParams(new android.widget.ScrollView.LayoutParams(maxWidth, android.widget.ScrollView.LayoutParams.MATCH_PARENT));
        		findViewById(R.id.console_scroller_x).setLayoutParams(new android.widget.ScrollView.LayoutParams(maxWidth, android.widget.ScrollView.LayoutParams.MATCH_PARENT));
        }}).enable();
        
        //Create custom output / error streams for the console
        outStream = new PrintStream(new ConsoleStream());
        errStream = new PrintStream(new ConsoleStream());
        
        //Set the custom output / error streams
        System.setOut(outStream);
        System.setErr(errStream);
        
        //Load default key bindings TODO load user's custom key bindings
        //Also, do this after we initialize the console so that we can get error reports
        
        keyBindings = new HashMap<String, KeyBinding>();
        
        try {
        	//Use Processing's XML for simplicity
			loadKeyBindings(new XML(getResources().getAssets().open("default_key_bindings.xml")));
		} catch (IOException e) { //Errors... who cares, anyway?
			e.printStackTrace();
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		} catch (SAXException e) {
			e.printStackTrace();
		}
    }
    
    public void onResume() {
    	super.onResume();
    	
    	//Reference the SharedPreferences text size value
    	((CodeEditText) findViewById(R.id.code)).refreshTextSize();
		((TextView) findViewById(R.id.console)).setTextSize(Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(this).getString("textsize_console", "14")));
    	
    	//Disable / enable the soft keyboard
        if(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("use_hardware_keyboard", false))
        	getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        else
        	getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        
        //Update the syntax highlighter
        ((CodeEditText) findViewById(R.id.code)).updateTokens();
    }
    
    /**
     * Load specified key bindings from the XML resource
     * 
     * @param xml
     */
    public void loadKeyBindings(XML xml) {
    	XML[] bindings = xml.getChildren();
    	for(XML binding : bindings) {
    		//Make sure that this is a "binding" element
    		if(!binding.getName().equals("binding"))
    			continue;
    		
    		//Parse the key binding
    		String name = binding.getContent();
    		int key = binding.getInt("key");
    		
    		//Load the list of possible key modifiers
    		String modifiers = binding.getString("mod");
    		String[] mods = modifiers.split("\\|"); // "|" is a REGEX keyword... need to escape it
    		
    		//The possible modifiers (these are available in API level 11+)
    		boolean ctrl = false;
    		boolean meta = false;
    		boolean func = false;
    		
    		// (and these are available across all API levels)
    		boolean alt = false;
    		boolean sym = false;
    		boolean shift = false;
    		
    		//This isn't very elegant... but it gets the job done
    		for(String mod : mods) {
    			if(mod.equals("ctrl")) ctrl = true;
    			if(mod.equals("meta")) meta = true;
    			if(mod.equals("func")) func = true;
    			
    			if(mod.equals("alt")) alt = true;
    			if(mod.equals("sym")) sym = true;
    			if(mod.equals("shift")) shift = true;
    		}
    		
    		//Build the KeyBinding
    		KeyBinding bind = new KeyBinding(name, key, ctrl, meta, func, alt, sym, shift);
    		
    		//Add the key binding
    		keyBindings.put(name, bind);
    	}
    }
    
    @SuppressLint("NewApi")
	@Override
    public boolean onKeyDown(int key, KeyEvent event) {
    	//Detect keyboard shortcuts
    	
    	// CTRL, META, and FUNCTION were added in Honeycomb...
    	// ...so we can't use them in 2.3
    	
    	// On older devices, we'll map:
    	//   CTRL      -->  ALT
    	//   META      -->  SYMBOL
    	//   FUNCTION  -->  SYMBOL
    	// 
    	// ...and yes, I know that META and FUNCTION map to the same value...
    	// ...but we can't exactly map CTRL+S to SHIFT+S, now can we?
    	
    	boolean ctrl;
		boolean meta;
		boolean func;
		
		boolean alt = event.isAltPressed();
    	boolean sym = event.isSymPressed();
    	boolean shift = event.isShiftPressed();
		
    	if(android.os.Build.VERSION.SDK_INT >= 11) {
    		//Read these values on API 11+
    		
    		ctrl = event.isCtrlPressed();
    		meta = event.isMetaPressed();
    		func = event.isFunctionPressed();
    	} else {
    		//Map these values to other ones on API 10 (app minimum)
    		
    		ctrl = alt;
    		meta = sym;
    		func = sym;
    	}
    	
    	//Check for the key bindings
    	//...this is where functional programming would come in handy
    	
    	if(keyBindings.get("save_sketch").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		saveSketch();
    		return true;
    	}
    	if(keyBindings.get("new_sketch").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		createNewSketch();
    		return true;
    	}
    	if(keyBindings.get("open_sketch").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		loadSketch();
    		return true;
    	}
    	
    	if(keyBindings.get("run_sketch").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		runApplication();
    		return true;
    	}
    	if(keyBindings.get("stop_sketch").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		stopApplication();
    		return true;
    	}
    	
    	//TODO implement these functions... they're place-holders for now
    	//TODO are these actually getting picked up?
    	if(keyBindings.get("comment").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		((CodeEditText) findViewById(R.id.code)).commentSelection();
    		return true;
    	}
    	if(keyBindings.get("shift_left").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		((CodeEditText) findViewById(R.id.code)).shiftLeft();
    		return true;
    	}
    	if(keyBindings.get("shift_right").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		((CodeEditText) findViewById(R.id.code)).shiftRight();
    		return true;
    	}
    	
    	if(keyBindings.get("new_tab").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		addTabWithDialog();
    		return true;
    	}
    	if(keyBindings.get("delete_tab").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		deleteTab();
    		return true;
    	}
    	if(keyBindings.get("rename_tab").matches(key, ctrl, meta, func, alt, sym, shift)) {
    		renameTab();
    		return true;
    	}
    	
		return super.onKeyDown(key, event);
    }
    
    /**
     * Saves the current sketch and sets up the editor with a blank sketch, from the context of the editor.
     */
    public void createNewSketch() {
    	//Make sure that the sketch isn't called "sketch"
    	if(getGlobalState().getSketchName().equals("sketch")) {
    		//If it is, we have to let the user know
    		
			AlertDialog.Builder alert = new AlertDialog.Builder(this);
	    	
	    	alert.setTitle(R.string.save_sketch_dialog_title);
	    	alert.setMessage(R.string.save_sketch_dialog_message);
	    	
	    	alert.setPositiveButton(R.string.save_sketch, new DialogInterface.OnClickListener() {
	    		public void onClick(DialogInterface dialog, int whichButton) {
	    			//Save the sketch
	    			autoSave();
	    			
	    			getGlobalState().setSketchName("sketch");
	    			getGlobalState().setSelectedSketch(-1);
	    			newSketch();
	    			forceDrawerReload();
	    			
	    			getSupportActionBar().setTitle(getGlobalState().getSketchName());
	    	}});
	    	
	    	//TODO neutral and negative seem mixed up, uncertain of correct implementation - current set up is for looks
	    	alert.setNeutralButton(R.string.dont_save_sketch, new DialogInterface.OnClickListener() {
	    		public void onClick(DialogInterface dialog, int whichButton) {
	    			getGlobalState().setSketchName("sketch");
	    			getGlobalState().setSelectedSketch(-1);
	    			getGlobalState().getEditor().newSketch();
	    			forceDrawerReload();
	    			
	    			getSupportActionBar().setTitle(getGlobalState().getSketchName());
	    	}});
	    	
	    	alert.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
	    		public void onClick(DialogInterface dialog, int whichButton) {
	    	}});
	    	
	    	//Show the soft keyboard if the hardware keyboard is unavailable (hopefully)
	    	AlertDialog dialog = alert.create();
	    	if(!PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("use_hardware_keyboard", false))
	    		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
	    	dialog.show();
		} else {
			//Save the sketch
			autoSave();
			
			//Update the global state
			getGlobalState().setSketchName("sketch");
			getGlobalState().setSelectedSketch(-1);
			
			//Set up for a new sketch
			newSketch();
			//Reload the navigation drawer
			forceDrawerReload();
			
			//Update the action bar title
			getSupportActionBar().setTitle(getGlobalState().getSketchName());
		}
    }
    
    /**
     * Called when the user selects "Load Sketch" - this will open the navigation drawer
     */
    private void loadSketch() {
    	//Opens the navigation drawer
    	
		DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer);
		LinearLayout drawerLayout = (LinearLayout) findViewById(R.id.drawer_wrapper);
		
		drawer.openDrawer(drawerLayout);
	}
    
    public int getSketchCount() {
    	//Get the location of the sketchbook
    	File sketchbookLoc = getGlobalState().getSketchbookFolder();
    	
    	int count = 0;
    	
    	//If the sketchbook exists
    	if(sketchbookLoc.exists()) {
    		//Get a list of sketches
	    	File[] folders = sketchbookLoc.listFiles();
	    	for(File folder : folders)
	    		if(folder.isDirectory())
	    			//Count this sketch
	    			count ++;
    	}
    	
    	return count;
    }
    
    /**
     * Fills an ArrayAdapter with the names of sketches found in the sketchbook folder
     * 
     * @param items
     */
    protected void populateWithSketches(ArrayAdapter<String> items) {
    	//Get the location of the sketchbook
    	File sketchbookLoc = getGlobalState().getSketchbookFolder();
    	
    	//If the sketchbook exists
    	if(sketchbookLoc.exists()) {
    		//Get a list of sketches
	    	File[] folders = sketchbookLoc.listFiles();
	    	
	    	for(File folder : folders)
	    		if(folder.isDirectory())
	    			//Add the sketch to the ArrayAdapter
	    			items.add(folder.getName());
    	}
	}
    
    /**
     * Fills an ArrayAdapter with the names of examples found in the examples folder (in assets)
     * 
     * @param items
     */
    protected void populateWithExamples(ArrayAdapter<String> items) {
    	//Get the location of the examples directory
    	File examplesLoc = getGlobalState().getExamplesFolder();
    	
    	//If the examples directory exists
    	if(examplesLoc.exists()) {
    		//Get a list of sketches
	    	File[] folders = examplesLoc.listFiles();
	    	
	    	for(File folder : folders)
	    		if(folder.isDirectory())
	    			//Add the sketch to the ArrayAdapter
	    			items.add(folder.getName());
    	}
	}
    
	//http://stackoverflow.com/questions/16983989/copy-directory-from-assets-to-data-folder
    private static boolean copyAssetFolder(AssetManager assetManager, String fromAssetPath, String toPath) {
    	try {
    		String[] files = assetManager.list(fromAssetPath);
    		new File(toPath).mkdirs();
    		boolean res = true;
    		for(String file : files)
    			if(file.contains("."))
    				res &= copyAsset(assetManager, fromAssetPath + "/" + file, toPath + "/" + file);
    			else 
    				res &= copyAssetFolder(assetManager, fromAssetPath + "/" + file, toPath + "/" + file);

    		return res;
    	} catch (Exception e) {
    		e.printStackTrace();
    		return false;
    	}
    }
    
	private static boolean copyAsset(AssetManager assetManager, String fromAssetPath, String toPath) {
		InputStream in = null;
		OutputStream out = null;
		try {
			in = assetManager.open(fromAssetPath);
			new File(toPath).createNewFile();
			out = new FileOutputStream(toPath);
			copyFile(in, out);
			in.close();
			in = null;
			out.flush();
			out.close();
			out = null;

			return true;
		} catch(Exception e) {
			e.printStackTrace();
			return false;
		}
	}
	
	private static void copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int read;
		while((read = in.read(buffer)) != -1)
			out.write(buffer, 0, read);
	}
	
	@Override
    public void onStop() {
		//Make sure to save the sketch
		saveSketchForStop();
		
    	super.onStop();
    }
	
	/**
	 * Saves the sketch for when the activity is closing
	 */
	public void saveSketchForStop() {
		//Automatically save
		autoSave();
    	
		//Store sketch info in private storage TODO make this SharedPreferences instead
		
		//Save the name of the current sketch
		String sketchName = getGlobalState().getSketchName();
    	writeTempFile("sketchName.txt", sketchName);
    	
    	//Save the index of the current sketch
    	String selectedSketch = Integer.toString(getGlobalState().getSelectedSketch());
		writeTempFile("sketchNum.txt", selectedSketch);
	}
	
	/**
	 * Loads the temporary sketch for the start of the app
	 * 
	 * @return success
	 */
	public boolean loadSketchStart() {
		try {
			//Load the temp files
			String sketchName = readTempFile("sketchName.txt");
			int selectedSketch = Integer.parseInt(readTempFile("sketchNum.txt"));
			
			//How's this for some nested if statements?
			
			//Check to see if this is a sketch or an example
			if(selectedSketch > getSketchCount() + 1) {
				//If the sketch is in the examples folder...
				if(getExampleLoc(sketchName).exists()) {
					//...load it
					getGlobalState().setSelectedSketch(selectedSketch);
					
					return loadExample(sketchName);
				} else {
					//Otherwise, load the sketch from the temp folder
					return loadSketchTemp();
				}
			} else {
				//If the sketch is in the sketchbook folder...
				if(getSketchLoc(sketchName).exists()) {
					//...load it
					getGlobalState().setSelectedSketch(selectedSketch);
					
					return loadSketch(sketchName);
				} else {
					//Otherwise, load the sketch from the temp folder
					return loadSketchTemp();
				}
			}
		} catch(Exception e) { //Meh...
			e.printStackTrace();
			return false;
		}
	}
	
	/**
	 * Automatically save the sketch, whether it is to the sketchbook folder or to the temp folder
	 */
	public void autoSave() {
		//No need to save if this is an example
		if(getGlobalState().isExample())
			return;
		
		//If the sketch exists in the sketchbook
		if(getSketchLoc(getGlobalState().getSketchName()).exists())
			//Save it to the sketchbook
			saveSketch();
		else
			//Save it to the temp folder
			saveSketchTemp();
	}
	
	/**
	 * Sets up the editor for a new sketch
	 */
	public void newSketch() {
		//Set the title of the action bar
		getSupportActionBar().setTitle(getGlobalState().getSketchName());
		//Reload the action bar actions and overflow
		supportInvalidateOptionsMenu();
		//Reload the navigation drawer
		forceDrawerReload();
		
		//Get rid of any existing tabs
		tabBar.removeAllTabs();
		tabs.clear();
		
		//Add the default "sketch" tab
		addDefaultTab("sketch");
		
		//Select the new tab
		tabBar.selectTab(0);
		
		//Clear the code area
		CodeEditText code = ((CodeEditText) findViewById(R.id.code));
		code.setUpdateText("");
	}
    
	/**
	 * Loads a sketch from the sketchbook folder
	 * 
	 * @param sketchName
	 * @return success
	 */
    public boolean loadSketch(String sketchName) {
    	//Get the sketch in the sketch folder
    	File sketchLoc = getSketchLoc(sketchName);
    	boolean success;
    	
    	//Ensure that the sketch folder exists and is a directory
    	if(sketchLoc.exists() && sketchLoc.isDirectory()) {
    		getGlobalState().setSketchName(sketchName);
    		
    		//Get all the files in the directory
    		File[] files = sketchLoc.listFiles();
    		
    		//Why do we need this...?
    		for(FileMeta meta : tabs.values())
    			meta.disable();
    		
    		//Get rid of any tabs
    		tabs.clear();
    		tabBar.removeAllTabs();
    		//This method is necessary, too
    		removeAllTabs();
    		
    		//Cycle through the files
    		for(File file : files) {
    			//Split the filename into prefix and suffix
    			String[] folders = file.getPath().split("/");
    			String[] parts = folders[folders.length - 1].split("\\.");
    			//If the filename isn't formatted properly, skip this file
    			if(parts.length != 2)
    				continue;
    			
    			//Retrieve the prefix and suffix
    			String prefix = parts[parts.length - 2];
    			String suffix = parts[parts.length - 1];
    			
    			//Check to see if it's a .PDE file
    			if(suffix.equals("pde")) {
    				//Build a Tab Meta object
    				FileMeta meta = new FileMeta("", "", 0, 0);
    				meta.readData(this, file.getAbsolutePath());
    				meta.setTitle(prefix);
    				
    				//Add the tab
    				addTab(prefix, meta);
    			}
    		}
    		
    		//Update the code-view
    		if(getSupportActionBar().getTabCount() > 0)
    			((CodeEditText) findViewById(R.id.code)).setUpdateText(tabs.get(tabBar.getSelectedTab()).getText());
    		else
    			((CodeEditText) findViewById(R.id.code)).setUpdateText("");
    		
    		success = true;
    		
    		//Automatically selects and loads the new tab
    		tabBar.selectLoadDefaultTab();
    	} else
    		success = false;
    	
    	if(success) {
    		getGlobalState().setExample(false);
    		//Make sure the code area is editable
//    		((CodeEditText) findViewById(R.id.code)).setEnabled(true);
    		((CodeEditText) findViewById(R.id.code)).setFocusable(true);
    		((CodeEditText) findViewById(R.id.code)).setFocusableInTouchMode(true);
//    		((CodeEditText) findViewById(R.id.code)).setClickable(true);
    	}
    	
    	return success;
    }
    
    /**
	 * Loads an example from the examples folder
	 * 
	 * @param sketchName
	 * @return success
	 */
    public boolean loadExample(String sketchName) {
    	//Get the sketch in the examples folder
    	File sketchLoc = getExampleLoc(sketchName);
    	boolean success;
    	
    	//Ensure that the sketch folder exists and is a directory
    	if(sketchLoc.exists() && sketchLoc.isDirectory()) {
    		getGlobalState().setSketchName(sketchName);
    		
    		//Get all the files in the directory
    		File[] files = sketchLoc.listFiles();
    		
    		//Why do we need this...?
    		for(FileMeta meta : tabs.values())
    			meta.disable();
    		
    		//Get rid of any tabs
    		tabs.clear();
    		tabBar.removeAllTabs();
    		//This method is necessary, too
    		removeAllTabs();
    		
    		//Cycle through the files
    		for(File file : files) {
    			//Split the filename into prefix and suffix
    			String[] folders = file.getPath().split("/");
    			String[] parts = folders[folders.length - 1].split("\\.");
    			//If the filename isn't formatted properly, skip this file
    			if(parts.length != 2)
    				continue;
    			
    			//Retrieve the prefix and suffix
    			String prefix = parts[parts.length - 2];
    			String suffix = parts[parts.length - 1];
    			
    			//Check to see if it's a .PDE file
    			if(suffix.equals("pde")) {
    				//Build a Tab Meta object
    				FileMeta meta = new FileMeta("", "", 0, 0);
    				meta.readData(this, file.getAbsolutePath());
    				meta.setTitle(prefix);
    				
    				//Add the tab
    				addTab(prefix, meta);
    			}
    		}
    		
    		//Update the code-view
    		if(getSupportActionBar().getTabCount() > 0)
    			((CodeEditText) findViewById(R.id.code)).setUpdateText(tabs.get(tabBar.getSelectedTab()).getText());
    		else
    			((CodeEditText) findViewById(R.id.code)).setUpdateText("");
    		
    		success = true;
    		
    		//Automatically selects and loads the new tab
    		tabBar.selectLoadDefaultTab();
    	} else
    		success = false;
    	
    	if(success) {
    		getGlobalState().setExample(true);
    		//Make sure the code area isn't editable
//    		((CodeEditText) findViewById(R.id.code)).setEnabled(false);
    		((CodeEditText) findViewById(R.id.code)).setFocusable(false);
    		((CodeEditText) findViewById(R.id.code)).setFocusableInTouchMode(false);
//    		((CodeEditText) findViewById(R.id.code)).setClickable(false);
    	}
    	
    	return success;
    }
    
    /**
     * Saves the sketch to the sketchbook folder, creating a new subdirectory if necessary
     */
    public void saveSketch() {
    	//If we cannot write to the external storage, make sure to inform the user
    	if(!externalStorageWritable()) {
    		AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getResources().getText(R.string.external_storage_dialog_title))
            	.setMessage(getResources().getText(R.string.external_storage_dialog_message)).setCancelable(false)
            	.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            		@Override
            		public void onClick(DialogInterface dialog, int which) {}
            }).show();
            
    		return;
    	}
    	
    	//Make sure that the sketch's name isn't "sketch"... and if it is, let the user change it
    	if(getGlobalState().getSketchName().equals("sketch")) {
    		changeSketchNameForSave();
    		return;
    	}
    	
    	
    	boolean success = true;
    	//Obtain the location of the sketch
    	File sketchLoc = getSketchLoc(getGlobalState().getSketchName());
    	
    	//Ensure that the sketch folder exists
    	sketchLoc.mkdirs();
    	
    	if(tabBar.getTabCount() > 0) {
	    	//Update the current tab
	    	EditText code = (EditText) findViewById(R.id.code);
	    	tabs.put(tabBar.getSelectedTab(), new FileMeta(tabBar.getSelectedTab().getText().toString(), code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd()));
	    	
	    	//Iterate through the FileMetas...
	    	for(FileMeta meta : tabs.values()) {
	    		if(meta.enabled())
	    			//...and write them to the sketch folder
	    			if(!meta.writeData(getApplicationContext(), sketchLoc.getPath() + "/"))
	    				success = false;
	    	}
	    	
	    	//Notify the user whether or not the sketch has been saved properly
	    	if(success) {
	    		//Force the drawer to reload
	    		forceDrawerReload();
	            
	            //Select the new sketch
	            if(getGlobalState().getSelectedSketch() < 0)
	            	getGlobalState().setSelectedSketch(((ListView) findViewById(R.id.drawer_list)).getCount() - 1);
	            
	            //Inform the user of success
	    		message(getResources().getText(R.string.sketch_saved));
	    		setSaved(true);
	    	} else
	    		//Inform the user of failure
	    		error(getResources().getText(R.string.sketch_save_failure));
    	} else {
    		//If there are no tabs
    		//TODO is this right?
    		
    		//Force the drawer to reload
    		forceDrawerReload();
    		
            //Select the first sketch
            getGlobalState().setSelectedSketch(0);
            
            //Inform the user
    		message(getResources().getText(R.string.sketch_saved));
    		setSaved(true);
    	}
    }
    
    private void changeSketchNameForSave() {
		AlertDialog.Builder alert = new AlertDialog.Builder(this);
    	
    	alert.setTitle(R.string.sketch_name_dialog_title);
    	alert.setMessage(R.string.sketch_name_dialog_message);
    	
    	final EditText input = new EditText(this);
    	input.setSingleLine();
    	input.setText(getGlobalState().getSketchName());
    	input.selectAll();
    	alert.setView(input);
    	
    	alert.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int whichButton) {
    			String before = getGlobalState().getSketchName();
    			String after = input.getText().toString();
    			
    			if(validateSketchName(after)) {
    				getGlobalState().setSketchName(after);
    				getSketchLoc(before).renameTo(getGlobalState().getEditor().getSketchLoc(after));
    				forceDrawerReload();
    				
    				//If the user has set the pretty name to the name of their sketch, they probably want to change the pretty name too
    				SharedPreferences prefs = getSharedPreferences(after, 0);
    		  		SharedPreferences.Editor ed = prefs.edit();
    		  		
    				if(prefs.getString("prop_pretty_name", "").equals(before))
    					ed.putString("prop_pretty_name", after);
    				
    				ed.commit();
    				
    				copyPrefs(before, after);
    				
    				saveSketch();
    			}
    	}});
    	
    	alert.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int whichButton) {
    	}});
    	
    	//Show the soft keyboard if the hardware keyboard is unavailable (hopefully)
    	AlertDialog dialog = alert.create();
    	if(!PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("use_hardware_keyboard", false))
    		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    	dialog.show();
    }
    
    //Copy all of the old preferences over to the new SharedPreferences and delete the old ones
  	public void copyPrefs(String before, String after) {
  		SharedPreferences old = getSharedPreferences(getGlobalState().getSketchName(), 0);
  		SharedPreferences prefs = getSharedPreferences(after, 0);
  		SharedPreferences.Editor ed = prefs.edit();
  		
  		for(Entry<String,?> entry : old.getAll().entrySet()){ 
  			Object v = entry.getValue(); 
  			String key = entry.getKey();

  			if(v instanceof Boolean)
  				ed.putBoolean(key, ((Boolean) v).booleanValue());
  			else if(v instanceof Float)
  				ed.putFloat(key, ((Float) v).floatValue());
  			else if(v instanceof Integer)
  				ed.putInt(key, ((Integer) v).intValue());
  			else if(v instanceof Long)
  				ed.putLong(key, ((Long) v).longValue());
  			else if(v instanceof String)
  				ed.putString(key, ((String) v));         
  		}
  		
  		ed.commit();
  		old.edit().clear().commit();
  	}
    
    private boolean validateSketchName(String name) {
		if(name.length() <= 0)
			return false;
		
		if(name.equals("sketch"))
			return false;
		
		return true;
	}
    
    /**
     * Deletes the current sketch
     */
    public void deleteSketch() {
    	//Obtain the location of the sketch folder
    	File sketchFolder = getSketchLoc(getGlobalState().getSketchName());
    	if(sketchFolder.isDirectory()) {
    		try {
    			//Perform recursive file deletion on the sketch folder
				deleteFile(sketchFolder);
			} catch (IOException e) {
				//catch stuff, shouldn't be any problems, though
				e.printStackTrace();
			}
    	}
    }
    
    //Recursive file deletion
    void deleteFile(File f) throws IOException {
    	if(f.isDirectory())
    		for(File c : f.listFiles())
    			deleteFile(c);
    	
    	if(!f.delete()) //Uh-oh...
    		throw new FileNotFoundException("Failed to delete file: " + f);
    }
    
    //Save the sketch into temp storage
    public void saveSketchTemp() {
    	//Erase previously stored files
    	File[] files = getFilesDir().listFiles();
    	for(File file : files)
    		file.delete();
    	
    	String sketchName = getGlobalState().getSketchName();
    	writeTempFile("sketchName.txt", sketchName);
    	String selectedSketch = Integer.toString(getGlobalState().getSelectedSketch());
    	writeTempFile("sketchNum.txt", selectedSketch);
    	
    	//Preserve tab order upon re-launch
    	String tabList = "";
    	for(int i = 0; i < tabBar.getTabCount(); i ++) {
    		String tabName = ((TextView) tabBar.getTabView(i).getChildAt(0)).getText().toString();
    		
    		//If it's a .PDE file, make sure to add the suffix
    		if(tabName.split(".").length <= 1)
    			tabName += ".pde";
    		
    		//Add the tab to the list
    		tabList += tabName + "\n";
    	}
    	
    	//Save the names of the tabs
    	writeTempFile("sketchFileNames", tabList);
    	
    	if(tabBar.getTabCount() > 0) {
    		//Update the current tab
    		EditText code = (EditText) findViewById(R.id.code);
    		tabs.put(tabBar.getSelectedTab(), new FileMeta(tabBar.getSelectedTab().getText().toString(), code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd()));
    	}
    	
    	//Iterate through the FileMetas...
    	for(FileMeta meta : tabs.values())
    		//...and write them to the sketch folder
    		meta.writeDataTemp(getApplicationContext());
    	
    	setSaved(true);
    }
    
    /**
     * Loads a sketch from the temp folder
     * 
     * @return success
     */
    public boolean loadSketchTemp() {
    	boolean success;
    	
    	try {
    		//Read the sketch name
    		String sketchName = readTempFile("sketchName.txt");
    		getGlobalState().setSketchName(sketchName);
    		//Read the sketch index
    		int selectedSketch = Integer.parseInt(readTempFile("sketchNum.txt"));
    		getGlobalState().setSelectedSketch(selectedSketch);
    		
    		//Read the list of tabs
    		String[] files = readTempFile("sketchFileNames").split("\n");
    		
    		//Iterate through the files
    		for(String filename : files) {
    			File file = new File(filename);
    			
    			//Split the filename into prefix and suffix
    			String[] folders = file.getPath().split("/");
    			String[] parts = folders[folders.length - 1].split("\\.");
    			//If the filename isn't formatted properly, skip this file
    			if(parts.length != 2)
    				continue;
    			
    			//Retrieve the prefix and suffix
    			String prefix = parts[parts.length - 2];
    			String suffix = parts[parts.length - 1];
    			
    			//Check to see if it's a .PDE file
    			if(suffix.equals("pde")) {
    				//Build a Tab Meta object
    				FileMeta meta = new FileMeta("", "", 0, 0);
    				meta.readTempData(this, file.getName());
    				meta.setTitle(prefix);
    				
    				//Add the tab
    				addTab(prefix, meta);
    			}
    		}
    		
    		//Automatically select and load the first tab
    		tabBar.selectLoadDefaultTab();
    		
    		success = true;
    	} catch(Exception e) { //Errors...
    		e.printStackTrace();
    		
    		success = false;
    	}
    	
    	return success;
    	
    }
    
    /**
     * Write text to a temp file
     * 
     * @param filename
     * @param text
     * @return success
     */
    public boolean writeTempFile(String filename, String text) {
    	BufferedOutputStream outputStream = null;
		boolean success;
		
		try {
			//Open the output stream
			outputStream = new BufferedOutputStream(openFileOutput(filename, Context.MODE_PRIVATE));
			//Write the data to the output stream
			outputStream.write(text.getBytes());
			
			success = true;
		} catch(Exception e) {
			e.printStackTrace();
			
			success = false;
			
		} finally {
			//Make sure to close the stream
			if(outputStream != null) {
				try {
					outputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		return success;
    }
    
    /**
     * Reads text from a temp file
     * 
     * @param filename
     * @return the text
     */
    public String readTempFile(String filename) {
    	BufferedInputStream inputStream = null;
    	String output = "";
    	
		try {
			//Open the input stream
			inputStream = new BufferedInputStream(openFileInput(filename));
			
			byte[] contents = new byte[1024];
			int bytesRead = 0;
			
			//Read the contents of the file
			while((bytesRead = inputStream.read(contents)) != -1)
				output += new String(contents, 0, bytesRead);
		} catch(Exception e) { //Umm... why is this here, again?
			e.printStackTrace();
		} finally {
			//Make sure to close the stream
			try {
				if(inputStream != null)
					inputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
    	
    	return output;
    }
    
    /**
     * @param sketchName
     * @return the File corresponding to the sketch folder of the specified sketch
     */
    public File getSketchLoc(String sketchName) {
    	//The location of the sketchbook
    	File sketchbookLoc = getGlobalState().getSketchbookFolder();
    	
    	//The location of the sketch
    	return new File(sketchbookLoc, sketchName);
    }
    
    /**
     * @param exampleName
     * @return the File corresponding to the sketch folder of the specified example
     */
    public File getExampleLoc(String exampleName) {
    	//The location of the sketchbook
    	File examplesLoc = getGlobalState().getExamplesFolder();
    	
    	//The location of the sketch
    	return new File(examplesLoc, exampleName);
    }
    
    /**
     * Reloads the navigation drawer
     */
    public void forceDrawerReload() {
        final ListView drawerList = (ListView) findViewById(R.id.drawer_list);

        //Create an ArrayAdapter to populate the drawer's list of sketches
        SectionedListAdapter sections = new SectionedListAdapter(this);
        
        ArrayAdapter<String> sketches = new ArrayAdapter<String>(this, R.layout.drawer_list_item);
        populateWithSketches(sketches);
        
        ArrayAdapter<String> examples = new ArrayAdapter<String>(this, R.layout.drawer_list_item);
        populateWithExamples(examples);
        
        sections.addSection(getResources().getString(R.string.drawer_list_title_sketchbook), sketches);
        sections.addSection(getResources().getString(R.string.drawer_list_title_examples), examples);

        //Load the list of sketches into the drawer
        drawerList.setAdapter(sections);
        drawerList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
    }
    
    /**
     * @return the APDE application global state
     */
    private APDE getGlobalState() {
    	return (APDE) getApplication();
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_editor, menu);
        
        if(drawerOpen) {
        	//If the drawer is visible
        	
        	//Make sure to hide all of the sketch-specific action items
        	menu.findItem(R.id.menu_run).setVisible(false);
        	menu.findItem(R.id.menu_stop).setVisible(false);
        	menu.findItem(R.id.menu_tab_delete).setVisible(false);
        	menu.findItem(R.id.menu_tab_rename).setVisible(false);
        	menu.findItem(R.id.menu_save).setVisible(false);
        	menu.findItem(R.id.menu_new).setVisible(false);
        	menu.findItem(R.id.menu_load).setVisible(false);
        	menu.findItem(R.id.menu_tab_new).setVisible(false);
        	menu.findItem(R.id.menu_sketch_properties).setVisible(false);
        	
        	//Make sure to hide the sketch name
        	getSupportActionBar().setTitle(R.string.app_name);
        } else {
        	if(tabBar.getTabCount() > 0) {
        		//If the drawer is closed and there are tabs
        		
        		//Make sure to make the tab actions visible
            	menu.findItem(R.id.menu_run).setVisible(true);
            	menu.findItem(R.id.menu_stop).setVisible(true);
            	menu.findItem(R.id.menu_tab_delete).setVisible(true);
            	menu.findItem(R.id.menu_tab_rename).setVisible(true);
            } else {
            	//If the drawer is closed and there are no tabs
            	
            	//Make sure to make the tab actions invisible
            	menu.findItem(R.id.menu_run).setVisible(false);
    	    	menu.findItem(R.id.menu_stop).setVisible(false);
    	    	menu.findItem(R.id.menu_tab_delete).setVisible(false);
            	menu.findItem(R.id.menu_tab_rename).setVisible(false);
            }
        	
        	//Make sure to make all of the sketch-specific actions visible
        	menu.findItem(R.id.menu_save).setVisible(true);
        	menu.findItem(R.id.menu_new).setVisible(true);
        	menu.findItem(R.id.menu_load).setVisible(true);
        	menu.findItem(R.id.menu_tab_new).setVisible(true);
        	menu.findItem(R.id.menu_sketch_properties).setVisible(true);
        	
        	//Make sure to make ths sketch name visible
        	getSupportActionBar().setTitle(getGlobalState().getSketchName());
        }
        
        //Disable these buttons because they appear when the tab is pressed
        //Not getting rid of them completely in case we want to change things in the future
        menu.findItem(R.id.menu_tab_new).setVisible(false);
        menu.findItem(R.id.menu_tab_delete).setVisible(false);
    	menu.findItem(R.id.menu_tab_rename).setVisible(false);
    	
    	//So that the user can add a tab if there are none
    	if(tabBar.getTabCount() <= 0)
    		menu.findItem(R.id.menu_tab_new).setVisible(true);
        
        return true;
    }
    
    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState(); //The stranger aspects of having a navigation drawer...
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig); //The stranger aspects of having a navigation drawer...
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
    	//When an action bar (or action bar overflow) action has been selected
    	
        switch(item.getItemId()) {
        	case android.R.id.home:
        		//The drawer toggle button
        		
        		//Get a reference to the drawer views
        		DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer);
        		LinearLayout drawerLayout = (LinearLayout) findViewById(R.id.drawer_wrapper);
        		
        		if(drawer.isDrawerOpen(drawerLayout)) {
        			//If the drawer is open, close it
                    drawer.closeDrawer(drawerLayout);
        		} else {
        			//If the drawer is closed, open it
                    drawer.openDrawer(drawerLayout);
                    
                    //Turn of the code area so that it cannot be focused (and so that the soft keyboard is hidden)
                    ((EditText) findViewById(R.id.code)).setEnabled(false);
                    supportInvalidateOptionsMenu();
                }
        		return true;
            case R.id.menu_run:
            	runApplication();
            	return true;
            case R.id.menu_stop:
            	stopApplication();
            	return true;
            case R.id.menu_save:
            	saveSketch();
            	return true;
            case R.id.menu_new:
            	createNewSketch();
            	return true;
            case R.id.menu_load:
            	loadSketch();
            	return true;
            case R.id.menu_settings:
            	launchSettings();
            	return true;
            case R.id.menu_tab_new:
            	addTabWithDialog();
            	return true;
            case R.id.menu_tab_rename:
            	renameTab();
            	return true;
            case R.id.menu_tab_delete:
            	deleteTab();
            	return true;
            case R.id.menu_sketch_properties:
            	launchSketchProperties();
            	return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
    
    /**
     * Builds and launches the current sketch
     * This CAN be called multiple times without breaking anything
     */
    private void runApplication() {
    	//No need to save an example...
    	if(!getGlobalState().isExample()) {
    		//Save the sketch
    		if(getSketchLoc(getSupportActionBar().getTitle().toString()).exists())
    			saveSketch();
    		else {
    			//If the sketch has yet to be saved, inform the user
    			AlertDialog.Builder builder = new AlertDialog.Builder(this);
    			builder.setTitle(getResources().getText(R.string.save_sketch_before_run_dialog_title))
    			.setMessage(getResources().getText(R.string.save_sketch_before_run_dialog_message)).setCancelable(false)
    			.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
    				@Override
    				public void onClick(DialogInterface dialog, int which) {}
    			}).show();
    			
    			return;
    		}
    	}
    	
    	//In case the user presses the button twice, we don't want any errors
    	if(building)
    		return;
    	
    	//Clear the console
    	((TextView) findViewById(R.id.console)).setText("");
    	
    	SharedPreferences prefs = getSharedPreferences(getGlobalState().getSketchName(), 0);
    	
    	final Build builder = new Build(getGlobalState());
    	Build.customManifest = new AtomicBoolean(prefs.getBoolean("use_custom_manifest", false));
    	Build.prettyName = new AtomicReference<String>(prefs.getString("prop_pretty_name", getGlobalState().getSketchName()));
    	
    	String[] perms = prefs.getString("permissions", "").split(",");
    	Build.perms = new AtomicReferenceArray<String>(perms.length);
    	for(int i = 0; i < perms.length; i ++)
    		Build.perms.set(i, perms[i]);
    	
    	Build.targetSdk = new AtomicInteger(Integer.parseInt(prefs.getString("prop_target_sdk", getResources().getString(R.string.prop_target_sdk_default))));
    	Build.orientation = new AtomicReference<String>(prefs.getString("prop_orientation", getResources().getString(R.string.prop_orientation_default)));
    	
    	//Build the sketch in a separate thread
    	Thread buildThread = new Thread(new Runnable() {
    		@Override
    		public void run() {
    			building = true;
    			builder.build("debug");
    			building = false;
    	}});
    	buildThread.start();
    }
    
    /**
     * Stops the current sketch's build process
     * This CAN be called multiple times without breaking anything
     */
    private void stopApplication() {
    	//This will stop the current build process
    	//I don't think we can stop a running app...
    	//...that's what the BACK button is for
    	
    	if(building)
    		Build.halt();
    }
    
    /**
     * Writes a message to the message area
     * 
     * @param msg
     */
    public void message(String msg) {
    	//Change the message area style
    	((TextView) findViewById(R.id.message)).setText(msg);
    	((TextView) findViewById(R.id.message)).setBackgroundColor(getResources().getColor(R.color.message_back));
    	//Write the message
    	((TextView) findViewById(R.id.message)).setTextColor(getResources().getColor(R.color.message_text));
    }
    
    /**
     * Writes a message to the message area from a non-UI thread
     * (this is used primarily from the build thread)
     * 
     * @param msg
     */
    public void messageExt(final String msg) {
    	runOnUiThread(new Runnable() {
    		public void run() {
    			//Change the message area style
    			((TextView) findViewById(R.id.message)).setText(msg);
    			((TextView) findViewById(R.id.message)).setBackgroundColor(getResources().getColor(R.color.message_back));
    			//Write the message
    			((TextView) findViewById(R.id.message)).setTextColor(getResources().getColor(R.color.message_text));
    		}
    	});
    }
    
    /**
     * Writes a message to the message area
     * This is a convenience method for message(String)
     * 
     * @param msg
     */
    public void message(CharSequence msg) {
    	message(msg.toString());
    }
    
    /**
     * Writes an error message to the message area
     * 
     * @param msg
     */
    public void error(String msg) {
    	//Change the message area style
    	((TextView) findViewById(R.id.message)).setText(msg);
    	((TextView) findViewById(R.id.message)).setBackgroundColor(getResources().getColor(R.color.error_back));
    	//Write the error message
    	((TextView) findViewById(R.id.message)).setTextColor(getResources().getColor(R.color.error_text));
    }
    
    /**
     * Writes an error message to the message area from a non-UI thread
     * (this is used primarily from the build thread)
     * 
     * @param msg
     */
    public void errorExt(final String msg) {
    	runOnUiThread(new Runnable() {
    		public void run() {
    			//Change the message area style
    			((TextView) findViewById(R.id.message)).setText(msg);
    	    	((TextView) findViewById(R.id.message)).setBackgroundColor(getResources().getColor(R.color.error_back));
    	    	//Write the error message
    	    	((TextView) findViewById(R.id.message)).setTextColor(getResources().getColor(R.color.error_text));
    		}
    	});
    }
    
    /**
     * Writes an error message to the message area
     * This is a convenience method for error(String)
     * 
     * @param msg
     */
    public void error(CharSequence msg) {
    	error(msg.toString());
    }
    
    /**
     * Highlights a code line (and opens the corresponding tab) in the code area from a non-UI thread
     * (this is used primarily from the build thread)
     * 
     * @param tab
     * @param line
     */
    public void highlightLineExt(final int tab, final int line) {
    	runOnUiThread(new Runnable() {
    		public void run() {
    			//Switch to the tab with the error
    			tabBar.selectTab(tab);
    			
    			//Get a reference to the code area
    			CodeEditText code = (CodeEditText) findViewById(R.id.code);
    			
    			//Calculate the beginning and ending of the line
    			int start = code.offsetForLine(line);
    			int stop = code.offsetForLineEnd(line);
    			
    			if(!PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("use_hardware_keyboard", false)) {
    				//Check to see if the user wants to use the hardware keyboard
    				
    				//Hacky way of focusing the code area - dispatches a touch event
    				MotionEvent me = MotionEvent.obtain(100, 0, 0, 0, 0, 0);
    				code.dispatchTouchEvent(me);
    				me.recycle();
    			}
    			
    			//Select the text in the code area
    			code.setSelection(start, stop);
    		}
    	});
    }
    
    /**
     * Creates a user input dialog for adding a new tab
     */
    private void addTabWithDialog() {
    	createInputDialog(getResources().getString(R.string.tab_new_dialog_title), getResources().getString(R.string.tab_new_dialog_message), "", NEW_TAB);
    }
    
    /**
     * Adds a default tab to the tab bar
     * 
     * @param title
     */
    private void addDefaultTab(String title) {
    	//Obtain a tab
    	ActionBar.Tab tab = getSupportActionBar().newTab();
    	//Set the tab text
    	tab.setText(title);
    	
    	//Add the tab
    	tabBar.addTab(tab);
    	tabs.put(tab, new FileMeta(title, "", 0, 0));
    	
    	//Make the tab non-all-caps
    	customizeTab(tab, title);
    }
    
    /**
     * Adds a tab to the tab bar
     * 
     * @param title
     */
	private void addTab(String title) {
		//Obtain a tab
    	ActionBar.Tab tab = getSupportActionBar().newTab();
    	//Set the tab text
    	tab.setText(title);
    	
    	//Add the tab
    	tabBar.addSelectTab(tab);
    	tabs.put(tab, new FileMeta(title, "", 0, 0));
    	
    	//Make the tab non-all-caps
    	customizeTab(tab, title);
	}
	
	/**
	 * Makes the tab non-all-caps (very hacky)
	 * 
	 * @param tab
	 * @param title
	 */
	@SuppressWarnings("deprecation")
	private void customizeTab(Tab tab, String title) {
		//Hacky way of making the tabs non-all-caps
		//Theoretically, we could do some other stuff here, too...
		
		//Get the tab view
    	TabView view = tabBar.getNewTabView();
    	
    	//Initialize some basic properties
    	view.setGravity(Gravity.CENTER);
    	view.setBackgroundColor(getResources().getColor(R.color.activity_background));
    	view.setBackgroundDrawable(getResources().getDrawable(R.drawable.tab_indicator_ab_holo));
    	
    	//Get the text view
    	TextView textView = (TextView) view.getChildAt(0);
    	
    	//Set up the text view
    	textView.setText(title);
    	textView.setTextColor(getResources().getColor(R.color.tab_text_color));
    	textView.setTypeface(Typeface.DEFAULT_BOLD);
    	textView.setTextSize(12);
    	
    	//Ensure that the tabs automatically scroll at some point
    	view.setMinimumWidth((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 80, getResources().getDisplayMetrics()));
    	
    	//Make sure that we can detect tab selection events
    	view.setOnClickListener(tabBar.getTabClickListener());
    }
	
	/**
	 * Adds a tab to the tab bar
	 * 
	 * @param title
	 * @param meta
	 */
	private void addTab(String title, FileMeta meta) {
		//Obtain a tab
    	ActionBar.Tab tab = getSupportActionBar().newTab();
    	//Set the tab text
    	tab.setText(title);
    	
    	//Add the tab
    	tabBar.addTab(tab);
    	tabs.put(tab, meta);
    	
    	//Make the tab non-all-caps
    	customizeTab(tab, title);
    }
    
	/**
	 * Creates a user input dialog for renaming the current tab
	 */
    private void renameTab() {
    	if(tabs.size() > 0)
    		createInputDialog(getResources().getString(R.string.tab_rename_dialog_title), getResources().getString(R.string.tab_rename_dialog_message), tabBar.getSelectedTab().getText().toString(), RENAME_TAB);
    }
    
    /**
     * Creates a user input dialog for deleting the current tab
     */
    private void deleteTab() {
    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.delete_dialog_title)
        	.setMessage(R.string.delete_dialog_message)
        	.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
        		@Override
        		public void onClick(DialogInterface dialog, int which) {
        	}})
        	.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
        		@Override
        		public void onClick(DialogInterface dialog, int which) {
        			deleteTabContinue();
        		}})
        .show();
    }
    
    /**
     * Deletes a file in the sketch folder
     * 
     * @param filename
     * @return success
     */
    private boolean deleteLocalFile(String filename) {
    	//Get the sketch folder location
    	File sketchLoc = getSketchLoc(getGlobalState().getSketchName());
    	//Get the file location
    	File file = new File(sketchLoc + "/", filename);
    	
    	//Delete the file
    	if(file.exists()) {
    		file.delete();
    		return true;
    	}
    	
    	return false;
    }
    
    //Called internally from delete tab dialog
    private void deleteTabContinue() {
    	if(tabs.size() > 0) {
    		//Get the tab
    		Tab cur = tabBar.getSelectedTab(); //TODO they'll be problems here for sure
    		//Delete the tab from the sketch folder
    		deleteLocalFile(tabs.get(cur).getFilename());
    		//Disable the tab
    		tabs.get(cur).disable();
    		//Remove the tab
    		tabBar.removeSelectedTab();
	    	tabs.remove(cur);
	    	
	    	//If there are no more tabs
	    	if(tabBar.getTabCount() <= 0) {
	    		//Clear the code text area
		    	CodeEditText code = ((CodeEditText) findViewById(R.id.code));
		    	code.setUpdateText("");
		    	code.setSelection(0);
	    		
	    		//Disable the code text area if there is no selected tab
	    		code.setEnabled(false);
	    		
	    		//Force remove all tabs
	    		tabBar.removeAllTabs();
	    		tabs.clear();
	    		
	    		//Force action menu refresh
	    		supportInvalidateOptionsMenu();
	    	}
	    	
	    	//Inform the user in the message area
	    	message(getResources().getText(R.string.tab_deleted));
    	}
    }
    
    /**
     * Force remove all tabs
     */
    public void removeAllTabs() {
    	//Note: This method is used because it seems that nothing else works.
    	
    	Iterator<Tab> it = tabs.keySet().iterator();
    	while(it.hasNext()) {
			it.next();
    		it.remove();
    	}
    }
    
    //Called internally to create an input dialog
    private void createInputDialog(String title, String message, String currentName, final int key) {
    	//Get a dialog builder
    	AlertDialog.Builder alert = new AlertDialog.Builder(this);
    	
    	//Set the title and message
    	alert.setTitle(title);
    	alert.setMessage(message);
    	
    	//Create an input field
    	final EditText input = new EditText(this);
    	input.setSingleLine();
    	input.setText(currentName);
    	input.selectAll();
    	
    	//Add the input field
    	alert.setView(input);
    	
    	//Add the "OK" button
    	alert.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int whichButton) {
    			String value = input.getText().toString();
    			checkInputDialog(key, true, value);
    		}
    	});
    	
    	//Add the "Cancel" button
    	alert.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int whichButton) {
    			checkInputDialog(key, false, "");
    		}
    	});
    	
    	//Show the soft keyboard if the hardware keyboard is unavailable (hopefully)
    	AlertDialog dialog = alert.create();
    	if(!PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("use_hardware_keyboard", false))
    		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    	dialog.show();
    }
    
    //Called internally when the add or rename tab dialog is closed
    private void checkInputDialog(int key, boolean completed, String value) {
    	if(completed) {
    		//Make sure that this is a valid name for a tab
    		if(!validateFileName(value))
    			return;
    		
    		switch(key) {
    		case RENAME_TAB:
    	    	//Get the tab
    			Tab cur = tabBar.getSelectedTab();
    	    	//Delete the tab from the sketch folder
    	    	deleteLocalFile(tabs.get(cur).getFilename());
    			
    	    	//Change the tab as it is displayed
    	    	((TextView) tabBar.getTabView(tabBar.getSelectedTab()).getChildAt(0)).setText(value); //This seems more complicated than it needs to be... but it's necessary to change the appearance of the tab name
    	    	
    	    	//Notify the user of success
    			message(getResources().getText(R.string.tab_renamed));
    			
    			break;
    		case NEW_TAB:
    			//Add the tab
    			addTab(value);
    			//Select the new tab
    			tabBar.selectLastTab();
    			//Perform a selection action
    			onTabSelected(tabBar.getSelectedTab());
    			
    			//Refresh options menu to remove "New Tab" button
    			if(tabBar.getTabCount() == 1)
    				supportInvalidateOptionsMenu();
    			
    			//Notify the user of success
    			message(getResources().getText(R.string.tab_created));
    			
    			break;
    		}
    	}
    }
    
	@Override
	public void onTabSelected(Tab tab) {
		//Get a reference to the code area
		CodeEditText code = ((CodeEditText) findViewById(R.id.code));
		//Get a reference to the current tab's meta
		FileMeta meta = tabs.get(tab);
		
		if(tabBar.getTabCount() > 0 && meta != null) {
			//If there are already tabs
			
			//Update the code area text
			code.setUpdateText(meta.getText());
			//Update the code area selection
			code.setSelection(meta.getSelectionStart(), meta.getSelectionEnd());
			code.updateBracketMatch();
		} else {
			//If this is selecting the first added tab
			
			//Re-enable the code text area
	    	code.setEnabled(true);
	    	
	    	//Force action menu refresh
    		supportInvalidateOptionsMenu();
		}
	}
	
	@Override
	public void onTabUnselected(Tab tab) {
		//Get a reference to the code area
		EditText code = ((EditText) findViewById(R.id.code));
		//Save the code area's meta
		tabs.put(tab, new FileMeta(tab.getText().toString(), code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd()));
	}
	
	@Override
	public void onTabReselected(Tab tab) {
		if(!drawerOpen) {
			//Get a reference to the anchor view for the popup window
			View anchorView = tabBar.getTabView(tabBar.getSelectedTab());
			
			//Create a PopupMenu anchored to a 0dp height "fake" view at the top if the display
			//This is a custom implementation, designed to support API level 10+ (Android's PopupMenu is 11+)
			PopupMenu popup = new PopupMenu(getGlobalState().getEditor(), anchorView);
			
			//Populate the actions
			MenuInflater inflater = getMenuInflater();
			inflater.inflate(R.menu.tab_actions, popup.getMenu());
			
			//Detect presses
			popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
				@Override
				public boolean onMenuItemClick(MenuItem item) {
					switch(item.getItemId()) {
					case R.id.menu_tab_new:
						addTabWithDialog();
						return true;
					case R.id.menu_tab_rename:
						renameTab();
						return true;
					case R.id.menu_tab_delete:
						deleteTab();
						return true;
					}

					return false;
				}
			});
			popup.show();
		}
	}
	
	/**
	 * @return whether or not we can write to the external storage
	 */
	private boolean externalStorageWritable() {
		String state = Environment.getExternalStorageState();
		if(Environment.MEDIA_MOUNTED.equals(state)) return true;
		else return false;
	}
	
	//Called internally to determine if a file / tab name is valid
	private boolean validateFileName(String title) {
		//Make sure that the tab name's length > 0
		if(title.length() <= 0) {
			error(getResources().getText(R.string.name_invalid_no_char));
			return false;
		}
		
		//Check to make sure that the first character isn't a number and isn't an underscore
		char first = title.charAt(0);
		if((first >= '0' && first <= '9') || first == '_') {
			error(getResources().getText(R.string.name_invalid_first_char));
			return false;
		}
		
		//Check all of the characters
		for(int i = 0; i < title.length(); i ++) {
			char c = title.charAt(i);
			if(c >= '0' && c <= '9') continue;
			if(c >= 'a' && c <= 'z') continue;
			if(c >= 'A' && c <= 'Z') continue;
			if(c == '_') continue;
			
			error(getResources().getText(R.string.name_invalid_char));
			return false;
		}
		
		//Make sure that this file / tab doesn't already exist
		for(FileMeta meta : tabs.values()) {
			if(meta.getTitle().equals(title)) {
				error(getResources().getText(R.string.name_invalid_same_title));
				return false;
			}
		}
		
		return true;
	}
	
	//Called internally to open the Sketch Properties activity
	private void launchSketchProperties() {
		Intent intent = new Intent(this, SketchPropertiesActivity.class);
		startActivity(intent);
	}
	
	//Called internally to open the Settings activity
	private void launchSettings() {
		Intent intent = new Intent(this, SettingsActivity.class);
		startActivity(intent);
	}
	
	/**
	 * NOTE: This is not currently correctly implemented
	 * 
	 * @return whether or not the sketch is saved
	 */
	public boolean isSaved() {
		//TODO make the is-saved functionality work
		
		return saved;
	}
	
	/**
	 * NOTE: This is not currently correctly implemented
	 * 
	 * @param saved
	 */
	public void setSaved(boolean saved) {
		this.saved = saved;
	}
	
	/**
	 * @return a map of tabs to their metas
	 */
	public HashMap<Tab, FileMeta> getTabs() {
		return tabs;
	}
	
	/**
	 * Add a message to the console and automatically scroll to the bottom (if the user has this feature turned on)
	 * 
	 * @param msg
	 */
	public void postConsole(String msg) {
		final TextView tv = (TextView) findViewById(R.id.console);
		
		//Add the text
		tv.append(msg);
		
		final ScrollView scroll = ((ScrollView) findViewById(R.id.console_scroller));
		
		//Scroll to the bottom (if the user has this feature enabled)
		if(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("pref_scroll_lock", true))
			scroll.post(new Runnable() {
				@Override
				public void run() {
					//Scroll to the bottom
					scroll.fullScroll(ScrollView.FOCUS_DOWN);
			}});
	}
	
	//Listener class for managing message area drag events
	public class MessageTouchListener implements android.view.View.OnLongClickListener, android.view.View.OnTouchListener {
		private boolean pressed;
		private int touchOff;
		
		private View code;
		private View console;
		private View content;
		
		public MessageTouchListener() {
			super();
			
			pressed = false;
			
			//Store necessary views globally
			code = findViewById(R.id.code_scroller);
			console = findViewById(R.id.console_scroller);
			content = findViewById(R.id.content);
		}
		
		@SuppressWarnings("deprecation")
		@Override
		public boolean onTouch(View view, MotionEvent event) {
			//Don't resize the console if there is no console to speak of
			if(keyboardVisible)
				return false;
			
			//Get the offset relative to the touch
			if(event.getAction() == MotionEvent.ACTION_DOWN)
				touchOff = (int) event.getY();
			
			if(pressed) {
				switch(event.getAction()) {
				case MotionEvent.ACTION_MOVE:
					//This doesn't work in the constructor for some reason
					if(message == 0)
						message = findViewById(R.id.message).getHeight();
					
					//Calculate maximum possible code view height
					int maxCode = content.getHeight() - message - tabBar.getHeight();
					
					//Find relative movement for this event
					int y = (int) event.getY() - touchOff;
					
					//Calculate the new dimensions of the console
					int consoleDim = console.getHeight() - y;
					if(consoleDim < 0)
						consoleDim = 0;
					if(consoleDim > maxCode)
						consoleDim = maxCode;
					
					//Calculate the new dimensions of the code view
					int codeDim = maxCode - consoleDim;
					
					//Set the new dimensions
					code.setLayoutParams(new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, codeDim));
					console.setLayoutParams(new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, consoleDim));
					
					firstResize = false;
					
					return true;
				case MotionEvent.ACTION_UP:
					pressed = false;
					
					//Change the message area drawable
					findViewById(R.id.message).setBackgroundDrawable(getResources().getDrawable(R.drawable.back)); //TODO this is deprecated...
					
					return true;
				}
			}
			
			return false;
		}
		
		@SuppressWarnings("deprecation")
		@Override
		public boolean onLongClick(View view) {
			//Don't resize the console if there is no console to speak of
			if(keyboardVisible)
				return false;
			
			pressed = true;
			
			//Change the message area drawable
			findViewById(R.id.message).setBackgroundDrawable(getResources().getDrawable(R.drawable.back_selected)); //TODO this is deprecated...
			
			//Provide haptic feedback (if the user has vibrations enabled)
			if(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("pref_vibrate", true))
				((android.os.Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(200); //200 millis
			
			return true;
		}
	}
	
	//Custom console output stream, used for System.out and System.err
	private class ConsoleStream extends OutputStream {
		final byte single[] = new byte[1];
		
		public ConsoleStream() {
		}
		
		public void close() { }
		
		public void flush() { }
		
		public void write(byte b[]) {
			write(b, 0, b.length);
		}
		
		@Override
		public void write(byte b[], int offset, int length) {
			final String value = new String(b, offset, length);
			
			runOnUiThread(new Runnable() {
				public void run() {
					//Write the value to the console
					postConsole(value);
			}});
		}
		
		@Override
		public void write(int b) {
			single[0] = (byte) b;
			write(single, 0, 1);
		}
	}
}