/*
 * Copyright 2026 Telconhn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.model;

import org.traccar.storage.StorageName;

/**
 * Un producto que puede ir en un vehículo. Es la misma idea que {@link Driver}: un código que manda
 * un lector y un nombre para mostrar en su lugar. La diferencia es de dónde sale el código (otras
 * entradas del GPS, nunca la del conductor) y que un vehículo lleva muchos a la vez: eso lo arma
 * {@link org.traccar.handler.ProductHandler}.
 *
 * Los vínculos (tc_user_product, tc_device_product, tc_group_product) salen solos del nombre de la
 * clase, igual que los de los conductores.
 */
@StorageName("tc_products")
public class Product extends ExtendedModel {

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private String uniqueId;

    public String getUniqueId() {
        return uniqueId;
    }

    public void setUniqueId(String uniqueId) {
        this.uniqueId = uniqueId.trim();
    }

    private String category;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category != null && !category.isBlank() ? category.trim() : null;
    }

}
