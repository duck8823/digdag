package acceptance.td;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.repackaged.com.google.common.base.Throwables;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Dataset;
import com.google.api.services.bigquery.model.DatasetList;
import com.google.api.services.bigquery.model.DatasetReference;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.bigquery.model.TableList;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.Objects;
import com.google.api.services.storage.model.StorageObject;
import io.digdag.client.DigdagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class GcpUtil
{
    private static final Logger logger = LoggerFactory.getLogger(GcpUtil.class);

    static final String GCP_CREDENTIAL = System.getenv().getOrDefault("GCP_CREDENTIAL", "");
    static final String GCS_TEST_BUCKET = System.getenv().getOrDefault("GCS_TEST_BUCKET", "");

    static final String GCP_PROJECT_ID;

    static {
        try {
            if (!GCP_CREDENTIAL.isEmpty()) {
                GCP_PROJECT_ID = DigdagClient.objectMapper().readTree(GCP_CREDENTIAL).get("project_id").asText();
            }
            else {
                GCP_PROJECT_ID = "";
            }
        }
        catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

    static final String GCS_PREFIX = "test/" + UUID.randomUUID().toString().replace('-', '_') + '/';
    static final String BQ_TAG = "test_" + UUID.randomUUID().toString().replace('-', '_');

    static void cleanupGcs(Storage gcs)
            throws Exception
    {
        if (gcs == null) {
            return;
        }

        Storage.Objects.List req = gcs.objects().list(GCS_TEST_BUCKET);
        Objects objects;
        do {
            objects = req.execute();
            List<StorageObject> items = objects.getItems();
            if (items != null) {
                for (StorageObject object : items) {
                    if (object.getName().startsWith(GCS_PREFIX)) {
                        try {
                            gcs.objects().delete(GCS_TEST_BUCKET, object.getName()).execute();
                        }
                        catch (IOException e) {
                            logger.warn("Failed to delete test gcs bucket: {}", object.getName(), e);
                        }
                    }
                }
            }
            req.setPageToken(objects.getNextPageToken());
        }
        while (null != objects.getNextPageToken());
    }

    static void cleanupBq(Bigquery bq, String gcpProjectId)
            throws IOException
    {
        if (bq == null) {
            return;
        }
        List<DatasetList.Datasets> datasets = listDatasets(
                bq, gcpProjectId, ds -> ds.getDatasetReference().getDatasetId().contains(BQ_TAG));
        for (DatasetList.Datasets dataset : datasets) {
            deleteDataset(bq, gcpProjectId, dataset.getDatasetReference().getDatasetId());
        }
    }

    static Table createTable(Bigquery bq, String projectId, String datasetId, String tableId)
            throws IOException
    {
        Table table = new Table()
                .setTableReference(new TableReference()
                        .setProjectId(projectId)
                        .setDatasetId(datasetId)
                        .setTableId(tableId));
        Table created = createTable(bq, projectId, datasetId, table);
        assertThat(tableExists(bq, projectId, datasetId, tableId), is(true));
        return created;
    }

    static boolean tableExists(Bigquery bq, String projectId, String datasetId, String tableId)
            throws IOException
    {
        try {
            bq.tables().get(projectId, datasetId, tableId).execute();
            return true;
        }
        catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    static Table createTable(Bigquery bq, String projectId, String datasetId, Table table)
            throws IOException
    {
        return bq.tables().insert(projectId, datasetId, table).execute();
    }

    static Dataset createDataset(Bigquery bq, String projectId, String datasetId)
            throws IOException
    {
        Dataset dataset = new Dataset()
                .setDatasetReference(new DatasetReference()
                        .setDatasetId(datasetId));
        Dataset created = createDataset(bq, projectId, dataset);
        assertThat(datasetExists(bq, projectId, datasetId), is(true));
        return created;
    }

    static Dataset createDataset(Bigquery bq, String projectId, Dataset dataset)
            throws IOException
    {
        return bq.datasets().insert(projectId, dataset).execute();
    }

    static boolean datasetExists(Bigquery bq, String projectId, String datasetId)
            throws IOException
    {
        try {
            bq.datasets().get(projectId, datasetId).execute();
            return true;
        }
        catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    static List<TableList.Tables> listTables(Bigquery bq, String projectId, String datasetId)
            throws IOException
    {
        return listTables(bq, projectId, datasetId, t -> true);
    }

    static List<TableList.Tables> listTables(Bigquery bq, String projectId, String datasetId, Predicate<TableList.Tables> needle)
            throws IOException
    {
        List<TableList.Tables> tables = new ArrayList<>();
        Bigquery.Tables.List req = bq.tables().list(projectId, datasetId);
        TableList tableList;
        do {
            tableList = req.execute();
            if (tableList.getTables() != null) {
                tableList.getTables().stream().filter(needle).forEach(tables::add);
            }
            req.setPageToken(tableList.getNextPageToken());
        }
        while (null != tableList.getNextPageToken());
        return tables;
    }

    static List<DatasetList.Datasets> listDatasets(Bigquery bq, String projectId)
            throws IOException
    {
        return listDatasets(bq, projectId, ds -> true);
    }

    static List<DatasetList.Datasets> listDatasets(Bigquery bq, String projectId, Predicate<DatasetList.Datasets> needle)
            throws IOException
    {
        List<DatasetList.Datasets> datasets = new ArrayList<>();
        Bigquery.Datasets.List req = bq.datasets().list(projectId);
        DatasetList datasetList;
        do {
            datasetList = req.execute();
            if (datasetList.getDatasets() != null) {
                datasetList.getDatasets().stream()
                        .filter(needle)
                        .forEach(datasets::add);
            }
            req.setPageToken(datasetList.getNextPageToken());
        }
        while (null != datasetList.getNextPageToken());
        return datasets;
    }

    static void deleteDataset(Bigquery bq, String gcpProjectId, String datasetId)
            throws IOException
    {
        // Delete tables
        List<TableList.Tables> tables = listTables(bq, gcpProjectId, datasetId, table -> true);
        for (TableList.Tables table : tables) {
            String tableId = table.getTableReference().getTableId();
            try {
                bq.tables().delete(gcpProjectId, datasetId, tableId).execute();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Delete dataset
        try {
            bq.datasets().delete(gcpProjectId, datasetId).execute();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}
