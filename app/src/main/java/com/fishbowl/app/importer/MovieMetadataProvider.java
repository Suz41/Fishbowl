package com.fishbowl.app.importer;

import android.content.Context;
import java.util.List;

/**
 * Modular metadata provider interface.
 */
public interface MovieMetadataProvider {
    List<MovieMetadata> searchMovie(String queryTitle, int year, Context context);
}
