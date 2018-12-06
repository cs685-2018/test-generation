package cs685.test.generation;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.apache.lucene.queryparser.classic.ParseException;
import org.kohsuke.stapler.DataBoundConstructor;

import cs685.test.selection.ir.InformationRetriever;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import io.reflectoring.diffparser.api.DiffParser;
import io.reflectoring.diffparser.api.UnifiedDiffParser;
import io.reflectoring.diffparser.api.model.Diff;

public class TestGenerationBuildWrapper extends BuildWrapper {

    private static final String REPORT_TEMPLATE_PATH = "/stats.html";
    private static final String PROJECT_NAME_VAR = "$PROJECT_NAME$";
    private static final String SELECTED_TESTS_VAR = "$SELECTED_TESTS$";

    @DataBoundConstructor
    public TestGenerationBuildWrapper() {
    }

    @Override
    public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener) {
    	// TODO: find a better method to display the test selection results than an html file
        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
              throws IOException, InterruptedException
            {
            	// Maven findbugs believes build.getWorkspace returns (or potentially returns) null at some point
            	// Error given is "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE"
            	if (build == null) {
            		throw new NullPointerException("TestGenerationBuildWrapper.setUp.tearDown: AbstractBuild object is null.");
            	}
            	else if (build.getWorkspace() == null) {
            		throw new NullPointerException("TestGenerationBuildWrapper.setUp.tearDown: AbstractBuild.getWorkspace() object is null.");
            	}
            	
            	// Get the selected tests
            	// TODO: change n (5) to be a tunable parameter by the user
            	Set<String> selectedTests = null;
                try {
					selectedTests = getSelectedTests(build.getWorkspace(), build, 5);
				} catch (ParseException e) {
					System.out.println("Error while parsing Java project:");
					e.printStackTrace();
				}
                
                // Split selected tests up by class
                Map<String, List<String>> selectedTestsMapper = new HashMap<String, List<String>>();
                for (String selectedTest : selectedTests) {
                	String[] selectedTestSplit = selectedTest.split("\\.");
                	if (selectedTest.length() != 2) {
                		System.out.println("Error with selected test <class>.<method> name: [" + selectedTest + "]");
                	} else {
                		String className = selectedTestSplit[0];
                		String methodName = selectedTestSplit[1];
                		if (selectedTestsMapper.containsKey(className)) {
                			selectedTestsMapper.get(className).add(methodName);
                		} else {
                			List<String> methods = new ArrayList<String>();
                			methods.add(methodName);
                			selectedTestsMapper.put(className, methods);
                		}
                	}
                }
                
                // Generate the Maven test selection string
                StringBuilder testSelection = new StringBuilder();
                int i = 0;
                for (String className : selectedTestsMapper.keySet()) {
                	testSelection.append(className);
                	testSelection.append("#");
                	testSelection.append(String.join("+", selectedTestsMapper.get(className)));
                	// TODO: How to separate classes for maven?
                	// For now, separate by comma
                	if (i+1 < selectedTestsMapper.keySet().size()) {
                		testSelection.append(", ");
                	}
                }
                System.out.println("Test selection string=[" + testSelection.toString() + "]");
                
                // TODO: execute selected tests
                // Temporary method to display selected tests
                String report = generateReport(build.getProject().getDisplayName(), testSelection.toString());
                
                // TODO: old method to generate the report
                //String report = generateReport(build.getProject().getDisplayName());
                File artifactsDir = build.getArtifactsDir();
                if (!artifactsDir.isDirectory()) {
                    boolean success = artifactsDir.mkdirs();
                    if (!success) {
                        listener.getLogger().println("Can't create artifacts directory at "
                          + artifactsDir.getAbsolutePath());
                    }
                }
                String path = artifactsDir.getCanonicalPath() + REPORT_TEMPLATE_PATH;
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path),
                  StandardCharsets.UTF_8))) {
                    writer.write(report);
                    writer.close();
                }
                return super.tearDown(build, listener);
            }
        };
    }

    // TODO: redo this method to return something else - DONE?
    private static Set<String> getSelectedTests(FilePath root, AbstractBuild build, int n) throws IOException, InterruptedException, ParseException {
    	HashMap<String, List<String>> classMap = new HashMap<String, List<String>>();
    	FilePath workspaceDir = root;
    	System.out.println("***TestGenerationBuildWrapper.buildStats.root (FilePath): " + workspaceDir);
    	
    	// Build the test selector (TODO: rename? used to get diffs?)
    	TestGeneration testSelector = new TestGeneration(classMap, workspaceDir, build);
    	
    	// Get the list of diffs
    	DiffParser parser = new UnifiedDiffParser();
        InputStream in = new ByteArrayInputStream(testSelector.getDifferences().getBytes());
        List<Diff> diffs = parser.parse(in);
        
        // Create the information retriever
    	InformationRetriever ir = new InformationRetriever(root, diffs);
    	
        Set<String> selectedTests = ir.getTestDocuments(n);
        ir.close();
        return selectedTests;
    }

    // TODO: old function to generate the HTML report file
    private static String generateReport(String projectName, String selectedTests) throws IOException {
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        try (InputStream in = TestGenerationBuildWrapper.class.getResourceAsStream(REPORT_TEMPLATE_PATH)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                bOut.write(buffer, 0, read);
            }
        }
        String content = new String(bOut.toByteArray(), StandardCharsets.UTF_8);
        content = content.replace(PROJECT_NAME_VAR, projectName);
        content = content.replace(SELECTED_TESTS_VAR, selectedTests);
                
        /*InformationRetriever.stopwords = new HashSet<String>();
        InformationRetriever.keywords = new HashSet<String>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
				InformationRetriever.class.getResourceAsStream(InformationRetriever.STOPWORDS_FILENAME)))) {
			for (String line; (line = br.readLine()) != null;) {
				InformationRetriever.stopwords.add(line.replaceAll("\\'", "")); // remove single quotes
			}
		}
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				InformationRetriever.class.getResourceAsStream(InformationRetriever.KEYWORDS_FILENAME)))) {
			for (String line; (line = br.readLine()) != null;) {
				InformationRetriever.keywords.add(line);
			}
		}*/
        
        return content;
    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Use test generation";
        }
    }
}
