import React, { useEffect, useState } from 'react';
import {
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
  NativeModules,
  Button,
  Switch,
  Alert,
  AppState,
  TouchableOpacity,
  FlatList,
  TextInput,
  Platform,
} from 'react-native';
import DateTimePicker from '@react-native-community/datetimepicker';

const { DetoxModule } = NativeModules;

type AppInfo = {
  packageName: string;
  appName: string;
  category?: string;
};

type AppSchedule = {
  start: string;
  end: string;
  unblockRequestedAt?: number;
};

type UsageInfo = {
  packageName: string;
  appName: string;
  totalTimeInForeground: number;
};

const App = () => {
  const [activeTab, setActiveTab] = useState<'dashboard' | 'apps' | 'settings'>('dashboard');

  const [hasUsagePermission, setHasUsagePermission] = useState(false);
  const [hasOverlayPermission, setHasOverlayPermission] = useState(false);
  const [hasDeviceAdmin, setHasDeviceAdmin] = useState(false);
  const [isServiceRunning, setIsServiceRunning] = useState(false);

  const [installedApps, setInstalledApps] = useState<AppInfo[]>([]);
  const [blockedApps, setBlockedApps] = useState<Record<string, AppSchedule>>({});
  const [usageStats, setUsageStats] = useState<UsageInfo[]>([]);
  
  const [searchQuery, setSearchQuery] = useState('');
  const [draftSchedules, setDraftSchedules] = useState<Record<string, {start: string, end: string}>>({});
  
  const [pomodoroWhitelist, setPomodoroWhitelist] = useState<string[]>([]);
  const [pomodoroEndTime, setPomodoroEndTime] = useState<number>(0);
  const [pomodoroRemaining, setPomodoroRemaining] = useState<string>('');
  const [pickerState, setPickerState] = useState<{
    visible: boolean;
    packageName: string;
    field: 'start' | 'end';
    currentValue: Date;
  } | null>(null);

  const [now, setNow] = useState(Date.now());

  const checkPermissions = async () => {
    const usage = await DetoxModule.checkUsageStatsPermission();
    const overlay = await DetoxModule.checkOverlayPermission();
    const admin = await DetoxModule.checkDeviceAdmin();
    setHasUsagePermission(usage);
    setHasOverlayPermission(overlay);
    setHasDeviceAdmin(admin);
  };

  const loadAppsAndSettings = async () => {
    try {
      const apps = await DetoxModule.getInstalledApps();
      apps.sort((a: AppInfo, b: AppInfo) => a.appName.localeCompare(b.appName));
      setInstalledApps(apps);

      const stats = await DetoxModule.getUsageStatsToday();
      stats.sort((a: UsageInfo, b: UsageInfo) => b.totalTimeInForeground - a.totalTimeInForeground);
      setUsageStats(stats.slice(0, 10)); // Top 10

      const blockedStr = await DetoxModule.getBlockedApps();
      let parsed = JSON.parse(blockedStr || '{}');
      if (Array.isArray(parsed)) {
        const migrated: Record<string, AppSchedule> = {};
        parsed.forEach(pkg => { migrated[pkg] = { start: "00:00", end: "23:59" } });
        parsed = migrated;
        DetoxModule.saveBlockedApps(JSON.stringify(parsed));
      }
      setBlockedApps(parsed);
      
      const serviceEnabled = await DetoxModule.getServiceStatus();
      setIsServiceRunning(serviceEnabled);

      const pState = await DetoxModule.getPomodoroState();
      setPomodoroEndTime(Number(pState.endTime));
      try {
        setPomodoroWhitelist(JSON.parse(pState.whitelist) || []);
      } catch (e) {}
    } catch (e) {
      console.error(e);
    }
  };

  useEffect(() => {
    const interval = setInterval(() => {
      if (pomodoroEndTime > Date.now()) {
        const remaining = pomodoroEndTime - Date.now();
        const mins = Math.floor(remaining / 60000);
        const secs = Math.floor((remaining % 60000) / 1000);
        setPomodoroRemaining(`${mins}:${secs.toString().padStart(2, '0')}`);
      } else {
        setPomodoroRemaining('');
      }
    }, 1000);
    return () => clearInterval(interval);
  }, [pomodoroEndTime]);

  useEffect(() => {
    checkPermissions();
    loadAppsAndSettings();

    const subscription = AppState.addEventListener('change', nextAppState => {
      if (nextAppState === 'active') checkPermissions();
    });
    const timer = setInterval(() => setNow(Date.now()), 10000);

    return () => {
      subscription.remove();
      clearInterval(timer);
    };
  }, []);

  const checkIsCurrentlyBlocked = (schedule: AppSchedule) => {
    if (!schedule || typeof schedule !== 'object' || !schedule.start || !schedule.end) return false;
    const d = new Date();
    const currentStr = `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
    if (schedule.start <= schedule.end) {
      return currentStr >= schedule.start && currentStr <= schedule.end;
    } else {
      return currentStr >= schedule.start || currentStr <= schedule.end;
    }
  };

  const toggleService = () => {
    if (!hasUsagePermission || !hasOverlayPermission) {
      Alert.alert("Permissions Required", "Please grant both Usage Stats and Overlay permissions first.");
      return;
    }
    if (isServiceRunning) {
      const hasActiveBlocks = Object.entries(blockedApps).some(([pkg, sched]) => pkg !== '_MASTER_COOLDOWN_' && checkIsCurrentlyBlocked(sched));
      if (hasActiveBlocks) {
        const masterCooldown = blockedApps['_MASTER_COOLDOWN_']?.start;
        if (!masterCooldown) {
          Alert.alert(
            "Detox Active 🛑",
            "You have actively blocked apps right now! Turning off the master switch requires a strict 1-HOUR cooldown.",
            [
              { text: "Cancel", style: "cancel" },
              { text: "Start 1-Hour Timer", style: "destructive", onPress: () => {
                  const newList = { ...blockedApps, '_MASTER_COOLDOWN_': { start: Date.now().toString(), end: "" } };
                  setBlockedApps(newList);
                  DetoxModule.saveBlockedApps(JSON.stringify(newList));
              }}
            ]
          );
          return;
        } else {
          const elapsedMs = now - parseInt(masterCooldown, 10);
          const remainingMinutes = Math.ceil((60 * 60 * 1000 - elapsedMs) / 60000);
          if (remainingMinutes > 0) {
            Alert.alert("Cooldown Active ⏳", `You must wait ${remainingMinutes} more minute(s) before you can turn off the service! No cheating!`);
            return;
          } else {
            const newList = { ...blockedApps };
            delete newList['_MASTER_COOLDOWN_'];
            setBlockedApps(newList);
            DetoxModule.saveBlockedApps(JSON.stringify(newList));
            DetoxModule.stopDetoxService();
            setIsServiceRunning(false);
          }
        }
      } else {
        DetoxModule.stopDetoxService();
        setIsServiceRunning(false);
      }
    } else {
      DetoxModule.startDetoxService();
      setIsServiceRunning(true);
    }
  };

  const toggleBlockApp = (packageName: string) => {
    let newBlockedList = { ...blockedApps };
    if (newBlockedList[packageName]) {
      const sched = newBlockedList[packageName];
      if (checkIsCurrentlyBlocked(sched)) {
        if (!sched.unblockRequestedAt) {
          Alert.alert(
            "Detox Active 🛑",
            "This app is currently blocked! Are you sure you want to unblock it? You will have to wait for a 10-minute cooldown.",
            [
              { text: "Cancel", style: "cancel" },
              { text: "Start 10m Timer", style: 'destructive', onPress: () => {
                  newBlockedList[packageName].unblockRequestedAt = Date.now();
                  setBlockedApps({...newBlockedList});
                  DetoxModule.saveBlockedApps(JSON.stringify(newBlockedList));
              }}
            ]
          );
          return;
        } else {
          const elapsedMs = Date.now() - sched.unblockRequestedAt;
          const remainingMinutes = Math.ceil((10 * 60 * 1000 - elapsedMs) / 60000);
          if (remainingMinutes > 0) {
            Alert.alert("Cooldown Active ⏳", `Wait ${remainingMinutes} more minute(s) before unblocking!`);
            return;
          } else {
            delete newBlockedList[packageName];
          }
        }
      } else {
        delete newBlockedList[packageName];
      }
    } else {
      const draft = draftSchedules[packageName];
      if (!draft || !draft.start || !draft.end) {
        Alert.alert("Set Timing First ⏰", "Please type your desired Start (HH:MM) and End (HH:MM) times in the boxes below before clicking Block.");
        return;
      }
      newBlockedList[packageName] = { start: draft.start, end: draft.end };
    }
    setBlockedApps(newBlockedList);
    DetoxModule.saveBlockedApps(JSON.stringify(newBlockedList));
  };

  const getButtonTitle = (app: AppInfo) => {
    const sched = blockedApps[app.packageName];
    if (!sched) return "Block";
    if (sched.unblockRequestedAt && checkIsCurrentlyBlocked(sched)) {
      const elapsedMs = now - sched.unblockRequestedAt;
      const remainingMin = Math.ceil((10 * 60 * 1000 - elapsedMs) / 60000);
      if (remainingMin > 0) return `Wait ${remainingMin}m`;
      return "Unblock Now";
    }
    return "Unblock";
  };

  const startPomodoro = async () => {
    if (!isServiceRunning) {
      Alert.alert("Service Disabled", "Please enable the Background Polling service first in Settings!");
      return;
    }
    Alert.alert(
      "Start Pomodoro?",
      "For the next 25 minutes, ALL apps will be strictly blocked except those you have whitelisted. Ready?",
      [
        { text: "Cancel", style: "cancel" },
        { text: "Start", onPress: async () => {
          const endTimeStr = await DetoxModule.startPomodoro(25);
          setPomodoroEndTime(Number(endTimeStr));
        }}
      ]
    );
  };

  const renderDashboard = () => {
    const maxTime = usageStats.length > 0 ? usageStats[0].totalTimeInForeground : 1;
    let totalMs = 0;
    usageStats.forEach(s => totalMs += s.totalTimeInForeground);
    const totalHrs = Math.floor(totalMs / 3600000);
    const totalMins = Math.floor((totalMs % 3600000) / 60000);

    return (
      <ScrollView style={styles.tabContent}>
        <Text style={styles.title}>Digital Detox Locker</Text>

        {/* Pomodoro Card */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Pomodoro Focus 🍅</Text>
          <Text style={styles.cardSubtitle}>Start a strict 25-minute timer.</Text>
          {pomodoroRemaining ? (
            <View style={{alignItems: 'center', marginVertical: 10}}>
              <Text style={{fontSize: 48, fontWeight: 'bold', color: '#BB86FC'}}>{pomodoroRemaining}</Text>
              <Text style={{color: '#ff4444', fontWeight: 'bold'}}>Strict Mode Active!</Text>
              <TouchableOpacity style={{marginTop: 15, padding: 10}} onPress={() => { setPomodoroEndTime(0); DetoxModule.startPomodoro(0); }}>
                <Text style={{color: '#888'}}>Stop Pomodoro (Cancel)</Text>
              </TouchableOpacity>
            </View>
          ) : (
            <TouchableOpacity style={styles.primaryButton} onPress={startPomodoro}>
              <Text style={styles.primaryButtonText}>Start 25-min Pomodoro</Text>
            </TouchableOpacity>
          )}
        </View>

        {/* Usage Stats Dashboard */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Today's Screen Time</Text>
          <Text style={{fontSize: 24, fontWeight: 'bold', color: '#FFFFFF', marginBottom: 15}}>{totalHrs}h {totalMins}m</Text>
          
          {usageStats.length === 0 ? (
            <Text style={{color: '#AAA'}}>No usage data recorded yet.</Text>
          ) : (
            usageStats.map((stat, i) => {
               const mins = Math.floor(stat.totalTimeInForeground / 60000);
               const hrs = Math.floor(mins / 60);
               const remainder = mins % 60;
               const timeStr = hrs > 0 ? `${hrs}h ${remainder}m` : `${mins}m`;
               const widthPercent = (stat.totalTimeInForeground / maxTime) * 100;
               
               return (
                 <View key={i} style={{marginBottom: 12}}>
                   <View style={{flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4}}>
                     <Text numberOfLines={1} style={{fontWeight: 'bold', color: '#FFF', flex: 1}}>{stat.appName}</Text>
                     <Text style={{color: '#AAA', fontWeight: 'bold'}}>{timeStr}</Text>
                   </View>
                   {/* Horizontal Bar */}
                   <View style={{width: '100%', height: 6, backgroundColor: '#333', borderRadius: 3}}>
                     <View style={{width: `${widthPercent}%`, height: '100%', backgroundColor: i === 0 ? '#BB86FC' : '#03DAC6', borderRadius: 3}} />
                   </View>
                 </View>
               );
            })
          )}
        </View>
      </ScrollView>
    );
  };

  const renderApps = () => {
    const groupedApps: Record<string, AppInfo[]> = {};
    installedApps.forEach(app => {
      const cat = app.category || 'Other';
      if (!groupedApps[cat]) groupedApps[cat] = [];
      groupedApps[cat].push(app);
    });

    const filteredCats = Object.keys(groupedApps).filter(cat => 
      groupedApps[cat].some(app => app.appName.toLowerCase().includes(searchQuery.toLowerCase()))
    ).sort();

    return (
      <View style={[styles.tabContent, {padding: 0}]}>
        <View style={{padding: 16, paddingBottom: 0}}>
          <Text style={styles.cardTitle}>App Locks & Whitelists</Text>
          <TextInput
            style={styles.searchInput}
            placeholder="Search for an app..."
            placeholderTextColor="#888"
            value={searchQuery}
            onChangeText={setSearchQuery}
          />
        </View>
        <ScrollView style={{flex: 1, paddingHorizontal: 16}}>
          {filteredCats.map(cat => (
            <View key={cat} style={{marginBottom: 20}}>
              <Text style={{color: '#BB86FC', fontWeight: 'bold', fontSize: 18, marginBottom: 8, marginTop: 8}}>{cat}</Text>
              {groupedApps[cat].filter(app => app.appName.toLowerCase().includes(searchQuery.toLowerCase())).map(app => renderAppItem(app))}
            </View>
          ))}
          <View style={{height: 40}}/>
        </ScrollView>
      </View>
    );
  };

  const renderAppItem = (app: AppInfo) => {
    const isBlocked = !!blockedApps[app.packageName];
    const draft = draftSchedules[app.packageName] || {start: "", end: ""};
    const isWhitelisted = pomodoroWhitelist.includes(app.packageName);

    const toggleWhitelist = async () => {
      let newList = [...pomodoroWhitelist];
      if (isWhitelisted) newList = newList.filter(p => p !== app.packageName);
      else newList.push(app.packageName);
      setPomodoroWhitelist(newList);
      await DetoxModule.savePomodoroWhitelist(JSON.stringify(newList));
    };

    const openPicker = (field: 'start' | 'end', currentVal: string) => {
      let date = new Date();
      if (currentVal && currentVal.includes(':')) {
        const [h, m] = currentVal.split(':').map(Number);
        date.setHours(h);
        date.setMinutes(m);
      }
      setPickerState({ visible: true, packageName: app.packageName, field, currentValue: date });
    };

    return (
      <View key={app.packageName} style={styles.appCard}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <View style={{ flex: 1, paddingRight: 10 }}>
            <Text style={{ fontSize: 16, fontWeight: 'bold', color: '#FFFFFF' }} numberOfLines={1}>{app.appName}</Text>
            <Text style={{ fontSize: 12, color: '#888' }}>{app.packageName}</Text>
          </View>
          <TouchableOpacity 
            style={[styles.smallButton, {backgroundColor: isBlocked ? '#ff4444' : '#03DAC6'}]}
            onPress={() => toggleBlockApp(app.packageName)}
          >
            <Text style={{color: '#121212', fontWeight: 'bold', fontSize: 12}}>{getButtonTitle(app)}</Text>
          </TouchableOpacity>
        </View>

        <View style={{flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10}}>
          <Text style={{fontSize: 13, color: '#AAA'}}>Allow during Pomodoro?</Text>
          <Switch value={isWhitelisted} onValueChange={toggleWhitelist} trackColor={{ true: '#BB86FC' }} />
        </View>

        <View style={{flexDirection: 'row', justifyContent: 'space-between'}}>
          <View style={{flex: 1, marginRight: 8}}>
            <Text style={{fontSize: 11, color: '#888', marginBottom: 4}}>Block Start</Text>
            <TouchableOpacity 
              disabled={isBlocked}
              onPress={() => openPicker('start', draft.start)}
              style={[styles.timeBox, isBlocked && {opacity: 0.5}]}
            >
              <Text style={{color: '#FFF', fontWeight: 'bold'}}>{isBlocked ? (blockedApps[app.packageName]?.start || "00:00") : (draft.start || "00:00")}</Text>
            </TouchableOpacity>
          </View>
          <View style={{flex: 1, marginLeft: 8}}>
            <Text style={{fontSize: 11, color: '#888', marginBottom: 4}}>Block End</Text>
            <TouchableOpacity 
              disabled={isBlocked}
              onPress={() => openPicker('end', draft.end)}
              style={[styles.timeBox, isBlocked && {opacity: 0.5}]}
            >
              <Text style={{color: '#FFF', fontWeight: 'bold'}}>{isBlocked ? (blockedApps[app.packageName]?.end || "23:59") : (draft.end || "23:59")}</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    );
  };

  const APP_VERSION = "1.0.0";

  const checkForUpdates = async () => {
    try {
      // Replace YOUR_GITHUB_USERNAME with the actual username after uploading
      const response = await fetch('https://api.github.com/repos/YOUR_GITHUB_USERNAME/DetoxApp/releases/latest');
      const data = await response.json();
      
      if (data.tag_name && data.tag_name !== `v${APP_VERSION}`) {
        Alert.alert(
          "Update Available! 🚀",
          `A new version (${data.tag_name}) is available. You are on v${APP_VERSION}.\n\nWould you like to download it?`,
          [
            { text: "Cancel", style: "cancel" },
            { text: "Download Update", onPress: () => {
              const apkAsset = data.assets.find((a: any) => a.name.endsWith('.apk'));
              if (apkAsset) {
                import('react-native').then(rn => rn.Linking.openURL(apkAsset.browser_download_url));
              } else {
                import('react-native').then(rn => rn.Linking.openURL(data.html_url));
              }
            }}
          ]
        );
      } else {
        Alert.alert("Up to Date", `You are running the latest version (v${APP_VERSION})!`);
      }
    } catch (e) {
      Alert.alert("Error", "Could not check for updates. Are you connected to the internet?");
    }
  };

  const renderSettings = () => (
    <ScrollView style={styles.tabContent}>
      <Text style={styles.title}>Settings</Text>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>App Updates</Text>
        <Text style={{color: '#AAA', fontSize: 12, marginBottom: 12}}>Current Version: v{APP_VERSION}</Text>
        <TouchableOpacity style={styles.primaryButton} onPress={checkForUpdates}>
          <Text style={styles.primaryButtonText}>Check for Updates</Text>
        </TouchableOpacity>
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Master Service Switch</Text>
        <View style={{flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginVertical: 10}}>
          <Text style={{fontSize: 16, color: '#FFF', fontWeight: 'bold'}}>Background Polling</Text>
          <Switch value={isServiceRunning} onValueChange={toggleService} trackColor={{true: '#03DAC6'}} />
        </View>
        <Text style={{fontSize: 12, color: '#AAA', marginBottom: 16}}>
          {isServiceRunning ? "Service is ACTIVE. Unlocking the master switch requires a 1-hour cooldown." : "Service is OFF."}
        </Text>
        <TouchableOpacity style={{backgroundColor: '#333', padding: 12, borderRadius: 8, alignItems: 'center'}} onPress={() => { DetoxModule.startDetoxService(); setIsServiceRunning(true); Alert.alert("Restarted", "Service forcefully restarted."); }}>
          <Text style={{color: '#FFF'}}>Force Restart Service</Text>
        </TouchableOpacity>
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Permissions</Text>
        
        <View style={styles.permRow}>
          <Text style={{color: '#FFF'}}>Usage Stats</Text>
          {hasUsagePermission ? <Text style={styles.granted}>Granted</Text> : <TouchableOpacity onPress={() => DetoxModule.requestUsageStatsPermission()}><Text style={styles.missing}>Fix</Text></TouchableOpacity>}
        </View>

        <View style={styles.permRow}>
          <Text style={{color: '#FFF'}}>Draw Overlay</Text>
          {hasOverlayPermission ? <Text style={styles.granted}>Granted</Text> : <TouchableOpacity onPress={() => DetoxModule.requestOverlayPermission()}><Text style={styles.missing}>Fix</Text></TouchableOpacity>}
        </View>

        <View style={styles.permRow}>
          <Text style={{color: '#FFF'}}>Device Admin</Text>
          {hasDeviceAdmin ? <Text style={styles.granted}>Granted</Text> : <TouchableOpacity onPress={() => DetoxModule.requestDeviceAdmin()}><Text style={styles.missing}>Fix</Text></TouchableOpacity>}
        </View>
        
        <TouchableOpacity style={{marginTop: 16, alignSelf: 'center'}} onPress={checkPermissions}>
          <Text style={{color: '#BB86FC'}}>Refresh Permissions</Text>
        </TouchableOpacity>
      </View>
    </ScrollView>
  );

  const handleTimeChange = (event: any, selectedDate?: Date) => {
    if (event.type === 'dismissed' || !selectedDate) {
      setPickerState(null);
      return;
    }
    if (pickerState) {
      const hours = selectedDate.getHours().toString().padStart(2, '0');
      const minutes = selectedDate.getMinutes().toString().padStart(2, '0');
      const timeStr = `${hours}:${minutes}`;
      const draft = draftSchedules[pickerState.packageName] || {start: "", end: ""};
      setDraftSchedules({...draftSchedules, [pickerState.packageName]: {...draft, [pickerState.field]: timeStr}});
      setPickerState(null);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      {activeTab === 'dashboard' && renderDashboard()}
      {activeTab === 'apps' && renderApps()}
      {activeTab === 'settings' && renderSettings()}
      
      {/* Bottom Navigation */}
      <View style={styles.bottomNav}>
        <TouchableOpacity style={styles.navItem} onPress={() => setActiveTab('dashboard')}>
          <Text style={[styles.navText, activeTab === 'dashboard' && styles.navTextActive]}>📊 Dashboard</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.navItem} onPress={() => setActiveTab('apps')}>
          <Text style={[styles.navText, activeTab === 'apps' && styles.navTextActive]}>🔒 App Locks</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.navItem} onPress={() => setActiveTab('settings')}>
          <Text style={[styles.navText, activeTab === 'settings' && styles.navTextActive]}>⚙️ Settings</Text>
        </TouchableOpacity>
      </View>

      {pickerState && pickerState.visible && (
        <DateTimePicker
          value={pickerState.currentValue}
          mode="time"
          is24Hour={true}
          display="default"
          onChange={handleTimeChange}
        />
      )}
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#121212',
  },
  tabContent: {
    flex: 1,
    padding: 16,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#BB86FC',
    marginVertical: 16,
    textAlign: 'center',
  },
  card: {
    backgroundColor: '#1E1E1E',
    padding: 16,
    borderRadius: 16,
    marginBottom: 16,
    elevation: 4,
  },
  cardTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#BB86FC',
    marginBottom: 4,
  },
  cardSubtitle: {
    color: '#AAA',
    fontSize: 12,
    marginBottom: 12,
  },
  primaryButton: {
    backgroundColor: '#BB86FC',
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
    marginTop: 8,
  },
  primaryButtonText: {
    color: '#121212',
    fontWeight: 'bold',
    fontSize: 16,
  },
  searchInput: {
    backgroundColor: '#2C2C2C',
    color: '#FFFFFF',
    borderRadius: 10,
    paddingHorizontal: 16,
    paddingVertical: 12,
    fontSize: 16,
    marginBottom: 10,
  },
  appCard: {
    backgroundColor: '#1E1E1E',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  smallButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
  },
  timeBox: {
    backgroundColor: '#2C2C2C',
    paddingVertical: 12,
    alignItems: 'center',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#333',
  },
  permRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#333',
  },
  granted: {
    color: '#03DAC6',
    fontWeight: 'bold',
  },
  missing: {
    color: '#121212',
    backgroundColor: '#ff4444',
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 12,
    fontWeight: 'bold',
    overflow: 'hidden',
  },
  bottomNav: {
    flexDirection: 'row',
    backgroundColor: '#1E1E1E',
    borderTopWidth: 1,
    borderTopColor: '#333',
    paddingBottom: Platform.OS === 'ios' ? 20 : 0,
  },
  navItem: {
    flex: 1,
    paddingVertical: 16,
    alignItems: 'center',
  },
  navText: {
    color: '#888',
    fontSize: 12,
    fontWeight: 'bold',
  },
  navTextActive: {
    color: '#BB86FC',
  }
});

export default App;
