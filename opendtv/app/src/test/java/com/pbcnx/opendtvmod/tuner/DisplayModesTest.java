package com.pbcnx.opendtvmod.tuner;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The pure half of the owner's five video-aspect modes: the stored-pref migration and the frame
 * geometry decision. Kept off the device so each rule is written down once and can regress loudly.
 */
public class DisplayModesTest {

    private static final float EPSILON = 0.0001f;
    private static final float ASPECT_16_9 = 16f / 9f;
    private static final float ASPECT_4_3 = 4f / 3f;

    // ---------------------------------------------------------------- stored values

    @Test
    public void unknownStoredValueFallsBackToAutomatic() {
        assertEquals(DisplayModes.ASPECT_AUTO, DisplayModes.aspectMode(-1));
        assertEquals(DisplayModes.ASPECT_AUTO, DisplayModes.aspectMode(99));
    }

    @Test
    public void everyFiveModeValueSurvivesNormalisation() {
        for (int mode = DisplayModes.ASPECT_AUTO; mode <= DisplayModes.ASPECT_ZOOM; mode++) {
            assertEquals(mode, DisplayModes.aspectMode(mode));
        }
    }

    @Test
    public void legacyThreeModesMigrateToTheFiveModeDomain() {
        assertEquals(DisplayModes.ASPECT_STRETCH, DisplayModes.migrateLegacyAspectMode(0));
        assertEquals(DisplayModes.ASPECT_AUTO, DisplayModes.migrateLegacyAspectMode(1));
        assertEquals(DisplayModes.ASPECT_ZOOM, DisplayModes.migrateLegacyAspectMode(2));
    }

    @Test
    public void unknownLegacyValueMigratesToAutomatic() {
        assertEquals(DisplayModes.ASPECT_AUTO, DisplayModes.migrateLegacyAspectMode(-5));
        assertEquals(DisplayModes.ASPECT_AUTO, DisplayModes.migrateLegacyAspectMode(42));
    }

    // ---------------------------------------------------------------- geometry

    @Test
    public void automaticFollowsA16By9VideoExactly() {
        DisplayModes.FrameGeometry g = DisplayModes.resolve(DisplayModes.ASPECT_AUTO, ASPECT_16_9, false);
        assertEquals(ASPECT_16_9, g.frameRatio, EPSILON);
        assertEquals(DisplayModes.RESIZE_FIT, g.resizeMode);
    }

    @Test
    public void automaticFollowsA4By3VideoExactly() {
        DisplayModes.FrameGeometry g = DisplayModes.resolve(DisplayModes.ASPECT_AUTO, ASPECT_4_3, false);
        assertEquals(ASPECT_4_3, g.frameRatio, EPSILON);
        assertEquals(DisplayModes.RESIZE_FIT, g.resizeMode);
    }

    @Test
    public void automaticClampsUnusualRatiosBetween4By3And16By9() {
        DisplayModes.FrameGeometry wide =
                DisplayModes.resolve(DisplayModes.ASPECT_AUTO, 2.39f, false);
        assertEquals(ASPECT_16_9, wide.frameRatio, EPSILON);
        DisplayModes.FrameGeometry tall =
                DisplayModes.resolve(DisplayModes.ASPECT_AUTO, 1.0f, false);
        assertEquals(ASPECT_4_3, tall.frameRatio, EPSILON);
    }

    @Test
    public void automaticUses16By9UntilTheVideoSizeIsKnown() {
        assertEquals(ASPECT_16_9,
                DisplayModes.resolve(DisplayModes.ASPECT_AUTO, 0f, false).frameRatio, EPSILON);
        assertEquals(ASPECT_16_9,
                DisplayModes.resolve(DisplayModes.ASPECT_AUTO, Float.NaN, false).frameRatio, EPSILON);
    }

    @Test
    public void stretchFillsTheScreenInLandscape() {
        DisplayModes.FrameGeometry g = DisplayModes.resolve(DisplayModes.ASPECT_STRETCH, ASPECT_4_3, true);
        assertEquals(0f, g.frameRatio, EPSILON);
        assertEquals(DisplayModes.RESIZE_FILL, g.resizeMode);
    }

    @Test
    public void stretchFillsTheVideoCardInPortrait() {
        DisplayModes.FrameGeometry g = DisplayModes.resolve(DisplayModes.ASPECT_STRETCH, ASPECT_4_3, false);
        assertEquals(ASPECT_16_9, g.frameRatio, EPSILON);
        assertEquals(DisplayModes.RESIZE_FILL, g.resizeMode);
    }

    @Test
    public void forceModesPinTheFrameAndFitTheContent() {
        DisplayModes.FrameGeometry fourThree =
                DisplayModes.resolve(DisplayModes.ASPECT_FORCE_4_3, ASPECT_16_9, true);
        assertEquals(ASPECT_4_3, fourThree.frameRatio, EPSILON);
        assertEquals(DisplayModes.RESIZE_FIT, fourThree.resizeMode);

        DisplayModes.FrameGeometry sixteenNine =
                DisplayModes.resolve(DisplayModes.ASPECT_FORCE_16_9, ASPECT_4_3, false);
        assertEquals(ASPECT_16_9, sixteenNine.frameRatio, EPSILON);
        assertEquals(DisplayModes.RESIZE_FIT, sixteenNine.resizeMode);
    }

    @Test
    public void displayAspectAppliesThePixelRatioOfAnamorphicStreams() {
        // 720x480 coded, square pixels = 3:2; ISDB-T 16:9 and 4:3 are the pixel-ratio variants.
        assertEquals(1.5f, DisplayModes.displayAspect(720, 480, 1f), EPSILON);
        assertEquals(ASPECT_16_9, DisplayModes.displayAspect(720, 480, 1.185185f), 0.001f);
        assertEquals(ASPECT_4_3, DisplayModes.displayAspect(720, 480, 0.888889f), 0.001f);
    }

    @Test
    public void displayAspectFallsBackTo16By9WithoutASize() {
        assertEquals(ASPECT_16_9, DisplayModes.displayAspect(0, 0, 1f), EPSILON);
        assertEquals(ASPECT_16_9, DisplayModes.displayAspect(-2, 480, 1f), EPSILON);
    }

    @Test
    public void displayAspectTreatsAnUnknownPixelRatioAsSquare() {
        assertEquals(1.5f, DisplayModes.displayAspect(720, 480, 0f), EPSILON);
        assertEquals(1.5f, DisplayModes.displayAspect(720, 480, Float.NaN), EPSILON);
    }

    @Test
    public void zoomCoversTheScreenInLandscapeAndTheCardInPortrait() {
        DisplayModes.FrameGeometry landscape =
                DisplayModes.resolve(DisplayModes.ASPECT_ZOOM, ASPECT_16_9, true);
        assertEquals(0f, landscape.frameRatio, EPSILON);
        assertEquals(DisplayModes.RESIZE_ZOOM, landscape.resizeMode);

        DisplayModes.FrameGeometry portrait =
                DisplayModes.resolve(DisplayModes.ASPECT_ZOOM, ASPECT_4_3, false);
        assertEquals(ASPECT_16_9, portrait.frameRatio, EPSILON);
        assertEquals(DisplayModes.RESIZE_ZOOM, portrait.resizeMode);
    }

    @Test
    public void namesAreTheOwnersWording() {
        assertEquals("Automatic (exact 16:9 / exact 4:3)",
                DisplayModes.aspectName(DisplayModes.ASPECT_AUTO));
        assertEquals("Stretch (ignores screen size)",
                DisplayModes.aspectName(DisplayModes.ASPECT_STRETCH));
        assertEquals("Force 4:3", DisplayModes.aspectName(DisplayModes.ASPECT_FORCE_4_3));
        assertEquals("Force 16:9", DisplayModes.aspectName(DisplayModes.ASPECT_FORCE_16_9));
        assertEquals("Zoom (crop to screen)", DisplayModes.aspectName(DisplayModes.ASPECT_ZOOM));
    }
}
