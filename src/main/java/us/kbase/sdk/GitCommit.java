package us.kbase.sdk;

import java.io.InputStream;
import java.util.Scanner;

/** The Git commit from which the SDK was built. Expects a file called "gitcommit" in the same
 * directory as the class file which contains the commit hash.
 * 
 * If the file is missing, the Git commit will be replaced with an error message.
 * @author gaprice@lbl.gov
 *
 */
public class GitCommit {
	
	// can't really test this easily since the file must be baked into the jar or war,
	// just test manually

	/** The Git commit from which the service was built. */
	public static final String COMMIT;
	
	private static final String COMMIT_FILE_NAME = "gitcommit";
	
	static {
		final InputStream is = GitCommit.class.getResourceAsStream(COMMIT_FILE_NAME);
		String commit = null;
		if (is == null) {
			commit = "Missing git commit file " + COMMIT_FILE_NAME +
					", should be in " + GitCommit.class.getPackage().getName();
		} else {
			final Scanner s = new Scanner(is);
			s.useDelimiter("\\A");
			commit = s.hasNext() ? s.next() : "";
			s.close();
		}
		COMMIT = commit.trim();
	}
	
}
