package com.julyerr.filedownloader;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlRootElement(namespace = "com.zhan_dui.downloader")
@XmlAccessorType(XmlAccessType.NONE)
public class DownloadMission {

    public static final int READY = 1;
    public static final int DOWNLOADING = 2;
    public static final int PAUSED = 3;
    public static final int FINISHED = 4;

    public static int DEFAULT_THREAD_COUNT = 4;

    @XmlElement(name = "URL")
    protected String mUrl;
    @XmlElement(name = "SaveDirectory")
    protected String mSaveDirectory;
    @XmlElement(name = "SaveName")
    protected String mSaveName;
    protected int mMissionID = MISSION_ID_COUNTER++;
    @XmlElementWrapper(name = "Downloadings")
    @XmlElement(name = "Downloading")
//    任务启动的线程数组
    private ArrayList<DownloadRunnable> mDownloadParts = new ArrayList<>();
//    恢复任务数组
    private ArrayList<RecoveryRunnableInfo> mRecoveryRunnableInfos = new ArrayList<>();

    @XmlElement(name = "MissionStatus")
    private int mMissionStatus = READY;

    private String mProgressDir;
    private String mProgressFileName;
    @XmlElement(name = "FileSize")
    private int mFileSize;
    private int mThreadCount = DEFAULT_THREAD_COUNT;
    private boolean isFinished = false;

    @XmlElement(name = "MissionMonitor")
    protected MissionMonitor mMonitor = new MissionMonitor(this);
    @XmlElement(name = "SpeedMonitor")
    protected SpeedMonitor mSpeedMonitor = new SpeedMonitor(this);

    protected StoreMonitor mStoreMonitor = new StoreMonitor();
    protected Timer mSpeedTimer = new Timer();
    protected Timer mStoreTimer = new Timer();

//    传入的线程池引用
    protected DownloadThreadPool mThreadPoolRef;

    private static int MISSION_ID_COUNTER = 0;

    static class RecoveryRunnableInfo {

        private int mStartPosition;
        private int mEndPosition;
        private int mCurrentPosition;
        private boolean isFinished = false;

        public RecoveryRunnableInfo(int start, int current, int end) {
            if (end > start && current > start) {
                mStartPosition = start;
                mEndPosition = end;
                mCurrentPosition = current;
            }
            if (mCurrentPosition >= mEndPosition) {
                isFinished = true;
            }
        }

        public int getStartPosition() {
            return mStartPosition;
        }

        public int getEndPosition() {
            return mEndPosition;
        }

        public int getCurrentPosition() {
            return mCurrentPosition;
        }

        public boolean isFinished() {
            return isFinished;
        }
    }

    @XmlRootElement(name = "MissionMonitor")
    @XmlAccessorType(XmlAccessType.NONE)
    static class MissionMonitor {

        public final DownloadMission mHostMission;
        @XmlElement(name = "DownloadedSize")
        @XmlJavaTypeAdapter(AtomicIntegerAdapter.class)
        //多线程可能同时进行操作
        private AtomicInteger mDownloadedSize = new AtomicInteger();

        public MissionMonitor() {
            mHostMission = null;
        }

        public MissionMonitor(DownloadMission monitorBelongsTo) {
            mHostMission = monitorBelongsTo;
        }

        public void down(int size) {
            mDownloadedSize.addAndGet(size);
            if (mDownloadedSize.intValue() == mHostMission.getFileSize()) {
                mHostMission.setDownloadStatus(FINISHED);
            }
        }

        public int getDownloadedSize() {
            return mDownloadedSize.get();
        }

    }

    @XmlRootElement
    @XmlAccessorType(XmlAccessType.NONE)
    private static class SpeedMonitor extends TimerTask {

        @XmlElement(name = "LastSecondSize")
        private int mLastSecondSize = 0;
        @XmlElement(name = "CurrentSecondSize")
        private int mCurrentSecondSize = 0;
        @XmlElement(name = "Speed")
        private int mSpeed;
        @XmlElement(name = "MaxSpeed")
        private int mMaxSpeed;
        @XmlElement(name = "AverageSpeed")
        private int mAverageSpeed;
        @XmlElement(name = "TimePassed")
        private int mCounter;

        private DownloadMission mHostMission;

        private SpeedMonitor() {
            // never use , for annotation
        }

        public int getMaxSpeed() {
            return mMaxSpeed;
        }

        public SpeedMonitor(DownloadMission missionBelongTo) {
            mHostMission = missionBelongTo;
        }

        @Override
//		每一秒时间更新速度
        public void run() {
            mCounter++;
            mCurrentSecondSize = mHostMission.getDownloadedSize();
            mSpeed = mCurrentSecondSize - mLastSecondSize;
            mLastSecondSize = mCurrentSecondSize;
            if (mSpeed > mMaxSpeed) {
                mMaxSpeed = mSpeed;
            }

            mAverageSpeed = mCurrentSecondSize / mCounter;
        }

        public int getDownloadedTime() {
            return mCounter;
        }

        public int getSpeed() {
            return mSpeed;
        }

        public int getAverageSpeed() {
            return mAverageSpeed;
        }
    }

    private class StoreMonitor extends TimerTask {
        @Override
        public void run() {
//			每5秒更新文件内容
            storeProgress();
        }
    }

    private DownloadMission() {
        // just for annotation
    }

    public DownloadMission(String url, String saveDirectory, String saveName)
            throws IOException {
        setTargetFile(saveDirectory, saveName);
        setProgessFile(mSaveDirectory, mSaveName);
    }

    public static DownloadMission newDownloadMission(String url, String saveDirectory, String saveName)
            throws IOException {
//        如果任务已经下载完成，返回
        if (isMission_Finished(url, saveDirectory, saveName)) {
            return null;
        }

        DownloadMission downloadMission = new DownloadMission(url, saveDirectory, saveName);
        return downloadMission;
    }

    public static boolean isMission_Finished(String url, String saveDirectory, String saveName)
            throws IOException {
        int size = getContentLength(url);
        if (saveDirectory.endsWith("/")) {
            saveDirectory = saveDirectory.substring(0, saveDirectory.length() - 1);
        }
        File file = new File(saveDirectory + "/" + saveName);
//        下载文件大小和网络请求所获文件大小相同
        if (file.length() == size) {
            return true;
        }
        return false;
    }

    public Boolean setTargetFile(String saveDir, String saveName)
            throws IOException {
        if (saveDir.lastIndexOf(File.separator) == saveDir.length() - 1) {
            saveDir = saveDir.substring(0, saveDir.length() - 1);
        }
        mSaveDirectory = saveDir;
        File dirFile = new File(saveDir);
        if (dirFile.exists() == false) {
            if (dirFile.mkdirs() == false) {
                throw new RuntimeException("Error to create directory");
            }
        }

        File file = new File(dirFile.getPath() + File.separator + saveName);
        if (file.exists() == false) {
            file.createNewFile();
        }
        mSaveName = saveName;
        return true;
    }

    public int getMissionID() {
        return mMissionID;
    }

    public String getUrl() {
        return mUrl;
    }

    public void setUrl(String Url) {
        this.mUrl = Url;
    }

    public String getSaveDirectory() {
        return mSaveDirectory;
    }

    public void setSaveDirectory(String SaveDirectory) {
        this.mSaveDirectory = SaveDirectory;
    }

    public String getSaveName() {
        return mSaveName;
    }

    public void setSaveName(String SaveName) {
        this.mSaveName = SaveName;
    }

    public void setMissionThreadCount(int thread_count) {
        mThreadCount = thread_count;
    }

    public int getMissionThreadCount() {
        return mThreadCount;
    }

    public void setDefaultThreadCount(int default_thread_count) {
        if (default_thread_count > 0)
            DEFAULT_THREAD_COUNT = default_thread_count;
    }

    public int getDefaultThreadCount() {
        return DEFAULT_THREAD_COUNT;
    }

    //    将整个文件平均分给每个线程
    private ArrayList<DownloadRunnable> splitDownload(int thread_count) {
        ArrayList<DownloadRunnable> runnables = new ArrayList<DownloadRunnable>();
        try {
            int size = getContentLength(mUrl);
            mFileSize = size;
            int sublen = size / thread_count;
            for (int i = 0; i < thread_count; i++) {
                int startPos = sublen * i;
                int endPos = (i == thread_count - 1) ? size
                        : (sublen * (i + 1) - 1);
                DownloadRunnable runnable = new DownloadRunnable(this.mMonitor,
                        mUrl, mSaveDirectory, mSaveName, startPos, endPos);
                runnables.add(runnable);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return runnables;
    }

    //    将xml文件内容恢复到对象中
    private void resumeMission() throws IOException {

        try {
            File progressFile = new File(FileUtils.getSafeDirPath(mProgressDir)
                    + File.separator + mProgressFileName);
            if (progressFile.exists() == false) {
                throw new IOException("Progress File does not exsist");
            }

            JAXBContext context = JAXBContext
                    .newInstance(DownloadMission.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            DownloadMission mission = (DownloadMission) unmarshaller
                    .unmarshal(progressFile);
            File targetSaveFile = new File(
                    FileUtils.getSafeDirPath(mission.mSaveDirectory
                            + File.separator + mission.mSaveName));
            if (targetSaveFile.exists() == false) {
                throw new IOException(
                        "Try to continue filedownloader file , but target file does not exist");
            }
//            将下载信息放入到recoveryRunnableInfo数组中
            ArrayList<RecoveryRunnableInfo> recoveryRunnableInfos = getDownloadProgress();
            recoveryRunnableInfos.clear();
            for (DownloadRunnable runnable : mission.mDownloadParts) {
                recoveryRunnableInfos.add(new RecoveryRunnableInfo(runnable
                        .getStartPosition(), runnable.getCurrentPosition(),
                        runnable.getEndPosition()));
            }
            mSpeedMonitor = new SpeedMonitor(this);
            mStoreMonitor = new StoreMonitor();
            System.out.println("Resume finished");
            mDownloadParts.clear();
        } catch (JAXBException e) {
//            e.printStackTrace();
        }
    }

    //    分为恢复和重新下载两个逻辑
    public void startMission(DownloadThreadPool threadPool) {
        try {
            resumeMission();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mThreadPoolRef = threadPool;
        if (mRecoveryRunnableInfos.size() != 0) {
//            将恢复结果剩余的未下载完毕的文件下载完毕
            for (RecoveryRunnableInfo runnableInfo : mRecoveryRunnableInfos) {
                if (runnableInfo.isFinished == false) {
                    setDownloadStatus(DOWNLOADING);
                    DownloadRunnable runnable = new DownloadRunnable(mMonitor,
                            mUrl, mSaveDirectory, mSaveName,
                            runnableInfo.getStartPosition(),
                            runnableInfo.getCurrentPosition(),
                            runnableInfo.getEndPosition());
                    mDownloadParts.add(runnable);
                    threadPool.submit(runnable);
                }
            }
        } else {
//            重新下载
            setDownloadStatus(DOWNLOADING);
            for (DownloadRunnable runnable : splitDownload(mThreadCount)) {
                mDownloadParts.add(runnable);
                threadPool.submit(runnable);
            }
        }
//        利于垃圾回收
        mRecoveryRunnableInfos = null;
        mSpeedTimer.scheduleAtFixedRate(mSpeedMonitor, 0, 1000);
        mStoreTimer.scheduleAtFixedRate(mStoreMonitor, 0, 5000);
    }

    public boolean isFinished() {
        return isFinished;
    }

    public void addPartedMission(DownloadRunnable runnable) {
        mDownloadParts.add(runnable);
    }

    private static int getContentLength(String fileUrl) throws IOException {
        URL url = new URL(fileUrl);
        URLConnection connection = url.openConnection();
        return connection.getContentLength();
    }

    private Boolean setProgessFile(String dir, String filename)
            throws IOException {
        if (dir.lastIndexOf(File.separator) == dir.length() - 1) {
            dir = dir.substring(0, dir.length() - 1);
        }
        File dirFile = new File(dir);
        if (dirFile.exists() == false) {
            if (dirFile.mkdirs() == false) {
                throw new RuntimeException("Error to create directory");
            }
        }
        mProgressDir = dirFile.getPath();
        File file = new File(dirFile.getPath() + File.separator + filename
                + ".tmp");
        if (file.exists() == false) {
            file.createNewFile();
        }
        mProgressFileName = file.getName();
        return true;
    }

    public File getProgressFile() {
        return new File(mProgressDir + File.separator + mProgressFileName);
    }

    public File getDownloadFile() {
        return new File(mSaveDirectory + File.separator + mSaveName);
    }

    public String getProgressDir() {
        return mProgressDir;
    }

    public String getProgressFileName() {
        return mProgressFileName;
    }

    public int getDownloadedSize() {
        return mMonitor.getDownloadedSize();
    }

    public String getReadableSize() {
        return DownloadUtils.getReadableSize(getDownloadedSize());
    }

    public int getSpeed() {
        return mSpeedMonitor.getSpeed();
    }

    public String getReadableSpeed() {
        return DownloadUtils.getReadableSpeed(getSpeed());
    }

    public int getMaxSpeed() {
        return mSpeedMonitor.getMaxSpeed();
    }

    public String getReadableMaxSpeed() {
        return DownloadUtils.getReadableSpeed(getMaxSpeed());
    }

    public int getAverageSpeed() {
        return mSpeedMonitor.getAverageSpeed();
    }

    public String getReadableAverageSpeed() {
        return DownloadUtils.getReadableSpeed(mSpeedMonitor.getAverageSpeed());
    }

    public int getTimePassed() {
        return mSpeedMonitor.getDownloadedTime();
    }

    public int getActiveTheadCount() {
        return mThreadPoolRef.getActiveCount();
    }

    public int getFileSize() {
        return mFileSize;
    }

    public void storeProgress() {
        try {
            JAXBContext context = JAXBContext
                    .newInstance(DownloadMission.class);
            Marshaller m = context.createMarshaller();
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            m.marshal(this, getProgressFile());
        } catch (JAXBException e) {
//            e.printStackTrace();
        }
    }

    public void pause() {
        setDownloadStatus(PAUSED);
        storeProgress();
        mThreadPoolRef.pause(mMissionID);
    }

    private void setDownloadStatus(int status) {
//        先设置好状态，然后保存到xml
        mMissionStatus = status;
        if (status == FINISHED) {
//            可以取消该任务
            cancel();
        }
    }

    public static DownloadMission recoverMissionFromProgressFile(
            String progressDirectory, String progressFileName)
            throws IOException {
        try {
            File progressFile = new File(
                    FileUtils.getSafeDirPath(progressDirectory)
                            + File.separator + progressFileName);
            if (progressFile.exists() == false) {
                throw new IOException("Progress File does not exsist");
            }

            JAXBContext context = JAXBContext
                    .newInstance(DownloadMission.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            DownloadMission mission = (DownloadMission) unmarshaller
                    .unmarshal(progressFile);
            File targetSaveFile = new File(
                    FileUtils.getSafeDirPath(mission.mSaveDirectory
                            + File.separator + mission.mSaveName));
            if (targetSaveFile.exists() == false) {
                throw new IOException(
                        "Try to continue filedownloader file , but target file does not exist");
            }
            mission.setProgessFile(progressDirectory, progressFileName);
            mission.mMissionID = MISSION_ID_COUNTER++;
            ArrayList<RecoveryRunnableInfo> recoveryRunnableInfos = mission
                    .getDownloadProgress();
            for (DownloadRunnable runnable : mission.mDownloadParts) {
                recoveryRunnableInfos.add(new RecoveryRunnableInfo(runnable
                        .getStartPosition(), runnable.getCurrentPosition(),
                        runnable.getEndPosition()));
            }
            mission.mDownloadParts.clear();
            return mission;
        } catch (JAXBException e) {
//            e.printStackTrace();
            return null;
        }
    }

    private void deleteProgressFile() {
        getProgressFile().delete();
    }

    public ArrayList<RecoveryRunnableInfo> getDownloadProgress() {
        return mRecoveryRunnableInfos;
    }


    public void cancel() {
        mSpeedTimer.cancel();
        mStoreMonitor.cancel();
        mDownloadParts.clear();
        mThreadPoolRef.cancel(mMissionID);
        deleteProgressFile();
    }
}
