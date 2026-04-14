package com.whut.map.map_service.shared.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AisProtocolConstantsTest {

    @Test
    void isValidCogCoversBoundaryCases() {
        assertThat(AisProtocolConstants.isValidCog(0.0)).isTrue();
        assertThat(AisProtocolConstants.isValidCog(359.9)).isTrue();
        assertThat(AisProtocolConstants.isValidCog(360.0)).isFalse();
        assertThat(AisProtocolConstants.isValidCog(Double.NaN)).isFalse();
        assertThat(AisProtocolConstants.isValidCog(-0.1)).isFalse();
    }

    @Test
    void isValidSogCoversBoundaryCases() {
        assertThat(AisProtocolConstants.isValidSog(0.0)).isTrue();
        assertThat(AisProtocolConstants.isValidSog(102.2999)).isTrue();
        assertThat(AisProtocolConstants.isValidSog(102.3)).isFalse();
        assertThat(AisProtocolConstants.isValidSog(Double.NaN)).isFalse();
        assertThat(AisProtocolConstants.isValidSog(-0.1)).isFalse();
    }
}