# API GestStock : guide du développeur Angular

Le contrat de l'API est le document **OpenAPI 3.0** généré par le serveur à partir du code
(contrôleurs, DTO et règles de validation). Il ne s'écrit pas à la main : il ne peut donc pas
diverger de l'implémentation, et un test (`OpenApiDocumentationIT`) bloque le build en cas
d'incohérence.

| Ressource | Où |
|---|---|
| Interface interactive | `http://localhost:8080/swagger-ui.html` (profils dev / test ; désactivée en prod) |
| Document JSON (vivant) | `http://localhost:8080/v3/api-docs` |
| Document JSON (instantané) | [`docs/openapi.json`](openapi.json) |
| Exemples (requêtes et réponses réelles) | `src/main/resources/openapi/examples/{operationId}.json`, intégrés au document |

## Utiliser Swagger UI

1. `POST /api/auth/login` → *Try it out* avec un compte (ex. l'administrateur initial).
2. Copier `accessToken` → bouton **Authorize** → coller le jeton (sans le préfixe `Bearer`).
   Le jeton reste mémorisé au rechargement (`persist-authorization`).
3. Renseigner `X-Warehouse-Id` sur les routes qui l'affichent pour choisir le dépôt.

## Générer les types / le client TypeScript

```bash
# Types seuls (léger, à combiner avec HttpClient) :
npx openapi-typescript http://localhost:8080/v3/api-docs -o src/app/core/api/geststock-api.d.ts

# Ou services Angular complets :
npx ng-openapi-gen --input http://localhost:8080/v3/api-docs --output src/app/core/api
```

Les noms de méthodes viennent des `operationId`, stables et explicites : `login`, `refreshToken`,
`listProduct`, `createSale`, `cancelSale`, `createCustomerReturn`, `openCashSession`,
`recordInventoryCounts`, `getDashboard`, `salesReport`… Les énumérations (statuts, moyens de
paiement, types de mouvement) et les **codes d'erreur** (`ApiError.code`) deviennent des types
union TypeScript.

## Règles à respecter côté front

| Sujet | Règle |
|---|---|
| Authentification | Intercepteur : `Authorization: Bearer <accessToken>` ; sur `401` (`INVALID_TOKEN`), un seul `POST /api/auth/refresh` puis rejouer la requête ; si le refresh échoue → écran de connexion. Le refresh token tourne à chaque appel : toujours stocker le dernier. |
| Droits | Afficher les menus d'après `GET /api/auth/me` (`permissions`) ; chaque opération documente sa permission (`x-permissions`, description). Le serveur reste l'autorité (`403 ACCESS_DENIED`). |
| Dépôt courant | En-tête `X-Warehouse-Id` sur les routes qui le déclarent (intercepteur, depuis le sélecteur de dépôt). |
| Erreurs | Lire `error.code` (stable) pour la logique, afficher `error.message` (français), `error.errors[]` pour surligner les champs, `error.details` pour le contexte (stock disponible, plafond…). Catalogue complet : description du document. |
| Listes | `{items, total, page, size, totalPages}` ; passer `page`/`size` pour paginer côté serveur. Rapports : `summary` = totaux de tout le filtre. |
| Montants | Le serveur calcule prix, remises, coûts et totaux ; ne pas envoyer de prix calculés (ils sont ignorés). Afficher les montants renvoyés. |
| Dates | Instants ISO-8601 UTC ; dates seules `AAAA-MM-JJ`. |
| Créations | `201` + en-tête `Location` ; corps = ressource créée. |

## Cohérence vérifiée par les tests

`OpenApiDocumentationIT` échoue si :

- une route du code n'est pas documentée, ou n'a pas de résumé, de tag, de réponse de succès ;
- un `operationId` est en double ou numéroté par défaut (`get_1`) ;
- une route protégée n'a pas `401`/`403` et sa permission, ou documente une permission différente
  de celle que le code vérifie (`@RequiresPermission` / `@PreAuthorize`) ;
- une route qui lit le dépôt courant n'expose pas `X-Warehouse-Id` (ou l'inverse) ;
- une méthode renvoyant `201`/`204` n'est pas documentée avec ce code ;
- un champ `@NotNull`/`@NotBlank`/`@NotEmpty` n'est pas `required`, une liste `@NotEmpty` n'a pas
  `minItems ≥ 1`, une limite `@Size`/`@Min`/`@Max` diffère du schéma (records imbriqués compris) ;
- le catalogue des codes d'erreur ne correspond pas à `ErrorCode` ;
- un exemple ne correspond à aucune opération, ou son corps de requête **échoue à la validation
  Bean Validation** du DTO, ou sa réponse ne se lit pas dans le type renvoyé.

## Mettre à jour

```bash
# Régénérer les exemples depuis un parcours métier réel (données annulées en fin de test) :
GESTSTOCK_TEST_TESTCONTAINERS=false ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=OpenApiExamplesHarvesterIT -Dopenapi.examples.write=true

# Exporter le document (target/openapi/openapi.json), puis copier l'instantané :
GESTSTOCK_TEST_TESTCONTAINERS=false ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=OpenApiDocumentationIT
cp target/openapi/openapi.json docs/openapi.json
```

Pour documenter une nouvelle route : `@Operation(summary, description)` et `@Tag` sur le
contrôleur, `@RequiresPermission`, `@Schema(example = …)` sur les champs de DTO,
`@ResponseStatus(HttpStatus.CREATED)` pour une création ; permissions, erreurs, en-tête de dépôt
et identifiant d'opération sont ajoutés automatiquement (`OpenApiCustomization`).
