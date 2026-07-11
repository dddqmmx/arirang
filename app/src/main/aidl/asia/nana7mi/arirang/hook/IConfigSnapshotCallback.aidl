package asia.nana7mi.arirang.hook;

oneway interface IConfigSnapshotCallback {
    void onConfig(long version, String snapshot);
}
