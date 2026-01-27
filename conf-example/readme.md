### Step 1
Change directory name from `conf-example` to `conf`

### Step 2
Rebuild module `amoro-ams` and start amoro

Stay inside the root directory of `olake-amoro` and run
```bash
./run.sh
```

It will take time, wait until you see "Login: admin/admin"

### Step 3
Register a Catalog into `olake-amoro`. 

There is a `destination.json` example in config directory. There is a `database-filer` and `table-filter` in that file, to filter out the necessary tables and databases.

Generate the `cookies.txt` (server-token). Everytime you start amoro (`./run.sh` command), you need to run this command to refresh the `cookies.txt` file.

```bash
./run.sh cookies
```

Register the Catalog

```bash
./run.sh register --destination <absolute_path/destination.json>
```

### Note

`./run.sh` will build the `amoro-ams` module along with its dependencies every time when you run it, in case you want to disable it comment out this line:
```bash
./mvnw install -DskipTests -pl amoro-ams -am
```